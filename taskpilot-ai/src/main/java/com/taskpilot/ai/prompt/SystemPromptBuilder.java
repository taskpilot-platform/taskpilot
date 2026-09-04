package com.taskpilot.ai.prompt;

import com.taskpilot.ai.heuristic.HeuristicConfigProvider;
import com.taskpilot.ai.service.SmartRoutingService;
import com.taskpilot.contracts.user.dto.UserProfileLiteDto;
import com.taskpilot.contracts.user.port.out.UserProfilePort;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds static and dynamic system prompts with user context, date, and workflow rules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final UserProfilePort userProfilePort;
    private final HeuristicConfigProvider heuristicConfigProvider;
    private final SmartRoutingService routingService;

    public String buildSystemPrompt(Long userId) {
        UserProfileLiteDto profile = userProfilePort.findLiteById(userId).orElse(null);
        String userName = profile != null ? profile.fullName() : "Unknown User";

        PromptTemplate template = PromptTemplate.from(AiPromptConstants.MASTER_PROMPT_TEMPLATE);
        return template.apply(Map.of(
                "current_date", LocalDate.now().toString(),
                "current_mode", heuristicConfigProvider.getCurrentMode(),
                "current_user_name", userName,
                "current_user_id", String.valueOf(userId)))
                .text();
    }

    public List<ChatMessage> withSystemPrompt(List<ChatMessage> history, String systemPrompt) {
        List<ChatMessage> updated = new ArrayList<>(history.size() + 1);
        updated.add(SystemMessage.from(systemPrompt));
        for (ChatMessage message : history) {
            if (message instanceof SystemMessage) {
                continue;
            }
            updated.add(message);
        }
        return updated;
    }

    public String buildCompactSystemPrompt(String originalPrompt) {
        int contextStart = originalPrompt.indexOf("[CURRENT SYSTEM CONTEXT]");
        int contextEnd = originalPrompt.indexOf("[TASKPILOT TOOL WORKFLOW RULES]");

        String contextSection;
        log.info("[AI Config] buildCompactSystemPrompt: contextStart={}, contextEnd={}", contextStart, contextEnd);
        if (contextStart != -1 && contextEnd != -1) {
            contextSection = originalPrompt.substring(contextStart, contextEnd).trim();
        } else {
            contextSection = """
                [CURRENT SYSTEM CONTEXT]
                - Today's Date: 2026-06-28
                - Current User: FuTie Neith (ID: 18)
                """;
        }

        return """

            You are the Backend Executor Agent of the TaskPilot system. Your SOLE purpose is to execute system instructions by calling the appropriate tools.
            YOU MUST NOT answer the user's question directly with text. YOU MUST ONLY call tools to fetch data or perform actions.
            EXCEPTION: If the user asks to perform an action or query but the required tool is not available, you MUST output exactly: MISSING_TOOL: <short reason>
            Do NOT generate any conversational or explanatory text.

            """ + contextSection + """

            [CRITICAL WORKFLOW RULES]
            - You MUST ONLY call tools that are explicitly defined in the 'tools' list of the current request. DO NOT call any other tools (e.g. 'smartQuery') if they are not in the 'tools' list of the current turn, even if you saw them in the conversation history.
            - PARALLEL & MULTI-TURN DAG EXECUTION:
              * You can and should call multiple independent tools in parallel (e.g., query projects and list notifications in the same turn).
              * DEPENDENCY RESOLUTION: If a write action (e.g., createTask, patchProject) depends on an ID (e.g., projectId) that you must query first (e.g., get the latest project the user participated in), you MUST call the query tool (e.g., smartQuery) first in Turn 1, and POSTPONE the write action to the next turn after you receive the query result. Do NOT guess or invent IDs.
            - smartQuery is STRICTLY for READ-only operations. It CANNOT perform CUD operations. If combined with a write action, call the write tool directly.
            - If querying 2+ different entities returned together (e.g. projects AND tasks, members AND workload), you MUST use `smartQuery` (ONLY if `smartQuery` is available in the 'tools' list of the current request). Note that fetching a single entity with a filter of another entity (e.g., querying comments on a task, or tasks in a sprint) is a single-entity query. You MUST use the specific tool (e.g. getMyTaskComments, queryTasks) instead of smartQuery. NEVER use smartQuery if the specific tool is available. However, if the specific tool is NOT available in the 'tools' list of the current request, you MUST use smartQuery to query that entity.
            - smartQuery rules:
              * To get all projects of current user, set `aggregate` to `""` (empty string).
              * Steps in the SAME chain run sequentially. Multiple chains run in PARALLEL.
              * Use 'ref' (e.g. {"projectId":"p"}) ONLY to map a parameter to a KEY of a PREVIOUS step in the SAME chain. Do NOT reference keys across different chains. DO NOT put fixed/raw IDs or values (like "1") in 'ref'.
              * If you have a fixed ID (like projectId "1"), you MUST put it directly in 'filters' (e.g. filters={"projectId":"1"}) and keep 'ref' empty (ref={}).
              * Supported entities: projects, tasks, members, sprints, comments, workload, notifications, skills. (Use 'members', NOT 'project_members').
              * When querying the "tasks" or "workload" or "members" entity, you MUST include "projectId" either directly in 'filters' (e.g. filters={"projectId":"1"}) or via 'ref' linking to a previous projects query key (e.g. ref={"projectId":"p"}). Exception: if querying a specific task by taskId/id (e.g. filters={"taskId":"1"} or filters={"id":"1"}) and the projectId is unknown, you may omit the projectId.
              * Example: Lấy dự án, thành viên, và task hôm nay của tôi:
                chains=[{"steps":[
                  {"key":"p","entity":"projects","filters":{},"ref":{},"aggregate":"","sort":"","limit":10},
                  {"key":"m","entity":"members","filters":{},"ref":{"projectId":"p"},"aggregate":"","sort":"","limit":50},
                  {"key":"t","entity":"tasks","filters":{"assigneeId":"me","dueToday":"true"},"ref":{"projectId":"p"},"aggregate":"","sort":"","limit":50}
                ]}]
            - CUD (WRITE) SPECIFIC RULES:
              * When moving a task to another status/column (e.g., TODO, IN_PROGRESS, DONE) without an explicit position, you MUST call updateTaskStatus. Only call moveTaskKanban if the user explicitly specifies a target position/order (e.g., 'vị trí số 2').
              * For entity updates where only some fields change, prefer the matching patch tool (e.g. patchProject, patchTask).
              * When assigning or delegating a task to a member (including yourself or 'me'), you MUST call assignTaskToMemberByName instead of patchTask. DO NOT use patchTask to update assigneeId.
              * To ADD a new personal skill (e.g. user says "thêm", "add"), you MUST call addMySkill. DO NOT call patchMySkill. Check the description of addMySkill for system skill ID (e.g. 1 for Java) and use it directly. DO NOT call searchSystemSkills.
              * To UPDATE or CHANGE the level of a skill the user ALREADY HAS (e.g. user says "tăng", "sửa", "cập nhật", "đổi", "update"), you MUST call patchMySkill directly. DO NOT call addMySkill.
              * To DELETE or REMOVE a personal skill from the user's profile, you MUST call deleteMySkill. DO NOT call deleteSystemSkill.
             - FORMS FOR CUD OPERATIONS: If you need to create a task, project, or sprint, but some information is missing (e.g. you don't know the title or name), you MUST call the write tool (e.g. createTask, createProject, createSprint) directly with null/empty values for the missing fields. DO NOT manually write a taskpilot-form block yourself. The tool itself will automatically detect the missing fields and return a form schema, which will be rendered for you.
            - REFERENCING LINK FORMAT: Always use relative markdown links when referencing resources in your response, which will automatically be resolved to local/deployment domain by frontend:
              * Project overview: [/projects/<projectId>/overview]
              * Task detail: [/projects/<projectId>/tasks/<taskId>]
              * Task comment: [/projects/<projectId>/tasks/<taskId>?commentId=<commentId>]
              * Notifications: [/notifications]
              * Comments: [/comments]
              * DO NOT use absolute URLs like http://localhost:5173 or https://taskpilot-platform.netlify.app.

            [STRICT OUTPUT RULES]
            1. Respond in Vietnamese by default.
            2. Output ONLY the tool calls immediately in JSON function call format or fenced `taskpilot-form` block. DO NOT write any thinking process, analysis, or explanation in <think> or <thought> tags. Do not write text.
            3. Keep your internal thinking process (reasoning) extremely brief (less than 15 words). Output tool calls as fast as possible.
            """;
    }

    public String buildSimplerGemmaSystemPrompt(boolean isWriteIntent) {
        if (isWriteIntent) {
            return """
                You are a tool execution agent for TaskPilot.
                Analyze the user request and call the appropriate write tool (e.g. createTask, createProject, etc.) to perform the action.
                Respond ONLY by calling the tool. Do NOT write any conversation or explanation.
                """;
        } else {
            return """
                You are a tool execution agent for TaskPilot.
                Analyze the user request and call the appropriate read/query tool (e.g. smartQuery, getMySkills, etc.) to fetch data.
                Respond ONLY by calling the tool. Do NOT write any conversation or explanation.
                """;
        }
    }

    public boolean isSimpleAction(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = routingService.normalize(userInput);

        boolean hasWriteVerb = false;
        for (String verb : List.of(
                "tao", "sua", "cap nhat", "xoa", "them", "gan", "giao", "chuyen", "huy bo", "xac nhan", "dong y",
                "create", "update", "patch", "delete", "remove", "add", "assign", "move", "cancel", "confirm"
        )) {
            if (normalized.contains(verb)) {
                hasWriteVerb = true;
                break;
            }
        }

        boolean hasQueryOrAnalysis = false;
        for (String keyword : List.of(
                "dong thoi", "danh sach", "liet ke", "hien co", "kiem tra", "truy van", "truy xuat", "lay cho toi",
                "workload", "tien do", "trang thai", "thong ke", "bao cao", "de xuat", "goi y", "recommend", "list",
                "xem chi tiet", "xem workload", "xem luong cong viec", "xem danh sach"
        )) {
            if (normalized.contains(keyword)) {
                hasQueryOrAnalysis = true;
                break;
            }
        }

        return hasWriteVerb && !hasQueryOrAnalysis;
    }

    public String getInitialStep(String userInput) {
        if (userInput == null) {
            userInput = "";
        }
        if (isSimpleAction(userInput)) {
            return "Chuẩn bị thực hiện yêu cầu...";
        }
        String inputLower = userInput.toLowerCase(Locale.ROOT);

        boolean isNotification = inputLower.contains("thông báo") || inputLower.contains("thong bao") || inputLower.contains("tb")
                || inputLower.contains("tin nhắn") || inputLower.contains("tin nhan") || inputLower.contains("chưa đọc")
                || inputLower.contains("chua doc") || inputLower.contains("unread");

        boolean isAssignment = inputLower.contains("phân công") || inputLower.contains("phan cong")
                || inputLower.contains("gợi ý") || inputLower.contains("goi y") || inputLower.contains("rcm")
                || inputLower.contains("gán") || inputLower.contains("gan") || inputLower.contains("assign")
                || inputLower.contains("kỹ năng") || inputLower.contains("ky nang") || inputLower.contains("skill")
                || inputLower.contains("ahp") || inputLower.contains("đề xuất") || inputLower.contains("de xuat");

        boolean isTask = inputLower.contains("task") || inputLower.contains("nhiệm vụ") || inputLower.contains("nhiem vu")
                || inputLower.contains("công việc") || inputLower.contains("cong viec") || inputLower.contains("nv")
                || inputLower.contains("việc") || inputLower.contains("viec") || inputLower.contains("deadline")
                || inputLower.contains("hạn") || inputLower.contains("han") || inputLower.contains("trễ")
                || inputLower.contains("tre") || inputLower.contains("to-do") || inputLower.contains("todo");

        boolean isProject = inputLower.contains("dự án") || inputLower.contains("du an") || inputLower.contains("da")
                || inputLower.contains("sprint") || inputLower.contains("mốc thời gian") || inputLower.contains("moc thoi gian")
                || inputLower.contains("tiến độ") || inputLower.contains("tien do") || inputLower.contains("tài liệu")
                || inputLower.contains("tai lieu") || inputLower.contains("tập tin") || inputLower.contains("tap tin")
                || inputLower.contains("thành viên") || inputLower.contains("thanh vien") || inputLower.contains("người tham gia")
                || inputLower.contains("nguoi tham gia");

        int matchedGroups = 0;
        if (isNotification) matchedGroups++;
        if (isAssignment) matchedGroups++;
        if (isTask) matchedGroups++;
        if (isProject) matchedGroups++;

        if (matchedGroups >= 2) {
            return "Phân tích các yêu cầu tổng hợp liên quan đến công việc và dự án...";
        }

        if (isNotification) {
            return "Phân tích yêu cầu về thông báo...";
        }

        if (isAssignment) {
            return "Phân tích yêu cầu về nhân sự và đề xuất phân công...";
        }

        if (isTask) {
            return "Phân tích yêu cầu về nhiệm vụ và công việc...";
        }

        if (isProject) {
            return "Phân tích yêu cầu về dự án và thành viên...";
        }

        return "Phân tích yêu cầu của bạn...";
    }

    public List<String> getPeriodicSteps(String userInput) {
        if (userInput == null) {
            userInput = "";
        }
        if (isSimpleAction(userInput)) {
            return List.of();
        }
        String inputLower = userInput.toLowerCase(Locale.ROOT);

        boolean isNotification = inputLower.contains("thông báo") || inputLower.contains("thong bao") || inputLower.contains("tb")
                || inputLower.contains("tin nhắn") || inputLower.contains("tin nhan") || inputLower.contains("chưa đọc")
                || inputLower.contains("chua doc") || inputLower.contains("unread");

        boolean isAssignment = inputLower.contains("phân công") || inputLower.contains("phan cong")
                || inputLower.contains("gợi ý") || inputLower.contains("goi y") || inputLower.contains("rcm")
                || inputLower.contains("gán") || inputLower.contains("gan") || inputLower.contains("assign")
                || inputLower.contains("kỹ năng") || inputLower.contains("ky nang") || inputLower.contains("skill")
                || inputLower.contains("ahp") || inputLower.contains("đề xuất") || inputLower.contains("de xuat");

        boolean isTask = inputLower.contains("task") || inputLower.contains("nhiệm vụ") || inputLower.contains("nhiem vu")
                || inputLower.contains("công việc") || inputLower.contains("cong viec") || inputLower.contains("nv")
                || inputLower.contains("việc") || inputLower.contains("viec") || inputLower.contains("deadline")
                || inputLower.contains("hạn") || inputLower.contains("han") || inputLower.contains("trễ")
                || inputLower.contains("tre") || inputLower.contains("to-do") || inputLower.contains("todo");

        boolean isProject = inputLower.contains("dự án") || inputLower.contains("du an") || inputLower.contains("da")
                || inputLower.contains("sprint") || inputLower.contains("mốc thời gian") || inputLower.contains("moc thoi gian")
                || inputLower.contains("tiến độ") || inputLower.contains("tien do") || inputLower.contains("tài liệu")
                || inputLower.contains("tai lieu") || inputLower.contains("tập tin") || inputLower.contains("tap tin")
                || inputLower.contains("thành viên") || inputLower.contains("thanh vien") || inputLower.contains("người tham gia")
                || inputLower.contains("nguoi tham gia");

        int matchedGroups = 0;
        if (isNotification) matchedGroups++;
        if (isAssignment) matchedGroups++;
        if (isTask) matchedGroups++;
        if (isProject) matchedGroups++;

        if (matchedGroups >= 2) {
            return List.of(
                    "Kết nối cơ sở dữ liệu kiểm tra thông tin liên quan...",
                    "Truy xuất danh sách dự án và thành viên tương ứng...",
                    "Rà soát danh sách nhiệm vụ và đối chiếu thời hạn (deadline)...",
                    "Đối chiếu thông tin và xử lý dữ liệu tổng hợp...",
                    "Chuẩn bị thông tin phản hồi chi tiết..."
            );
        }

        if (isNotification) {
            return List.of(
                    "Kết nối dịch vụ thông báo để kiểm tra dữ liệu...",
                    "Truy xuất danh sách các thông báo chưa đọc...",
                    "Phân tích nội dung và sắp xếp thông báo theo thời gian...",
                    "Cập nhật trạng thái hiển thị các thông báo mới...",
                    "Tổng hợp thông tin thông báo chi tiết..."
            );
        }

        if (isAssignment) {
            return List.of(
                    "Truy xuất thông tin kỹ năng và khối lượng công việc của thành viên...",
                    "Đánh giá yêu cầu kỹ năng và độ khó của nhiệm vụ...",
                    "Áp dụng mô hình phân tích AHP để đánh giá độ phù hợp...",
                    "So sánh hiệu suất và mức độ sẵn sàng của các thành viên...",
                    "Tối ưu hóa phương án đề xuất phân công công việc..."
            );
        }

        if (isTask) {
            return List.of(
                    "Kiểm tra danh sách nhiệm vụ được giao cho bạn...",
                    "Rà soát thời hạn (deadline) và mức độ ưu tiên của các công việc...",
                    "Kiểm tra các task đến hạn hoặc bị trễ hạn...",
                    "Phân tích trạng thái tiến độ các nhiệm vụ hiện tại...",
                    "Tổng hợp thông tin công việc chi tiết..."
            );
        }

        if (isProject) {
            return List.of(
                    "Truy xuất danh sách dự án bạn tham gia...",
                    "Tải thông tin chi tiết và tiến độ các dự án...",
                    "Kiểm tra danh sách thành viên trong các dự án liên quan...",
                    "Rà soát các mốc thời gian và trạng thái hoạt động của dự án...",
                    "Tổng hợp thông tin dự án chi tiết..."
            );
        }

        return List.of(
                "Kết nối cơ sở dữ liệu để kiểm tra thông tin...",
                "Truy xuất các thông tin liên quan từ cơ sở dữ liệu...",
                "Tiến hành đối chiếu và kiểm tra tính toàn vẹn của dữ liệu...",
                "Lập phương án xử lý tối ưu cho yêu cầu...",
                "Chuẩn bị phản hồi chi tiết..."
        );
    }
}
