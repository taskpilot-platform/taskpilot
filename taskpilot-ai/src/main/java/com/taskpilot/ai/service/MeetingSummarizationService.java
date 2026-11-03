package com.taskpilot.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.contracts.workspace.chat.ChatMessageSummaryDto;
import com.taskpilot.contracts.workspace.meeting.MeetingDetailDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceKind;
import com.taskpilot.contracts.workspace.meeting.MeetingSourceTextDto;
import com.taskpilot.contracts.workspace.meeting.MeetingSummaryDto;
import com.taskpilot.contracts.workspace.meeting.ProjectMeetingPort;
import com.taskpilot.contracts.workspace.meeting.TaskActionItemDto;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MeetingSummarizationService {

    private final ProjectMeetingPort projectMeetingPort;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModelProvider;

    public MeetingSummarizationService(
            ProjectMeetingPort projectMeetingPort,
            ObjectMapper objectMapper,
            @Qualifier("geminiFlashChatModel") ObjectProvider<ChatModel> chatModelProvider) {
        this.projectMeetingPort = projectMeetingPort;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.chatModelProvider = chatModelProvider;
    }

    public MeetingSummaryDto summarizeMeeting(
            Long projectId,
            Long meetingId,
            String sourceKindPreference,
            Boolean forceRegenerate,
            Long requesterUserId) {

        log.info("[MeetingSummarization] summarizeMeeting: projectId={}, meetingId={}, sourceKind={}, forceRegenerate={}, user={}",
                projectId, meetingId, sourceKindPreference, forceRegenerate, requesterUserId);

        // 1. Check existing summary unless forceRegenerate is true
        if (forceRegenerate != Boolean.TRUE) {
            MeetingDetailDto details = projectMeetingPort.getMeetingDetails(projectId, meetingId, requesterUserId);
            if (details != null && details.aiSummaryJson() != null && !details.aiSummaryJson().isBlank()) {
                try {
                    MeetingSummaryDto cached = parseSavedSummaryJson(details.aiSummaryJson(), meetingId, details.meeting().title());
                    if (cached != null) {
                        log.info("[MeetingSummarization] Returning cached summary for meeting #{}", meetingId);
                        return cached;
                    }
                } catch (Exception e) {
                    log.warn("[MeetingSummarization] Failed to parse cached summary JSON: {}", e.getMessage());
                }
            }
        }

        // 2. Fetch source text
        MeetingSourceTextDto sourceText = projectMeetingPort.getMeetingSourceText(projectId, meetingId, requesterUserId);
        if (sourceText == null || (isEmpty(sourceText.transcript()) && isEmpty(sourceText.notes())
                && (sourceText.chatWindowMessages() == null || sourceText.chatWindowMessages().isEmpty())
                && isEmpty(sourceText.description()))) {
            return new MeetingSummaryDto(
                    meetingId,
                    sourceText != null ? sourceText.title() : "Meeting #" + meetingId,
                    "Cuộc họp chưa có bản ghi, ghi chú hoặc nội dung trao đổi nào để tạo tóm tắt.",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    MeetingSourceKind.NONE,
                    Instant.now(),
                    false);
        }

        // 3. Select source kind
        MeetingSourceKind sourceUsed = resolveSourceKind(sourceKindPreference, sourceText);
        String unifiedContent = buildUnifiedContent(sourceText, sourceUsed);

        // 4. Summarize via LLM or heuristic fallback
        MeetingSummaryDto summary = null;
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null && !unifiedContent.isBlank()) {
            try {
                summary = generateLlmSummary(chatModel, meetingId, sourceText.title(), unifiedContent, sourceUsed);
            } catch (Exception e) {
                log.warn("[MeetingSummarization] LLM summarization failed: {}. Falling back to heuristic extractor.", e.getMessage());
            }
        }

        if (summary == null) {
            summary = generateHeuristicSummary(meetingId, sourceText.title(), sourceText, sourceUsed);
        }

        // 5. Save summary to DB
        try {
            String jsonToSave = objectMapper.writeValueAsString(summary);
            projectMeetingPort.saveAiSummary(projectId, meetingId, jsonToSave, sourceUsed.name(), requesterUserId);
        } catch (Exception e) {
            log.error("[MeetingSummarization] Failed to serialize and save AI summary: {}", e.getMessage());
        }

        return summary;
    }

    private MeetingSourceKind resolveSourceKind(String preference, MeetingSourceTextDto sourceText) {
        if (preference != null && !preference.isBlank()) {
            try {
                MeetingSourceKind requested = MeetingSourceKind.valueOf(preference.trim().toUpperCase(Locale.ROOT));
                if (hasContentFor(requested, sourceText)) {
                    return requested;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        return sourceText.sourceKind() != null ? sourceText.sourceKind() : MeetingSourceKind.NONE;
    }

    private boolean hasContentFor(MeetingSourceKind kind, MeetingSourceTextDto sourceText) {
        return switch (kind) {
            case TRANSCRIPT -> !isEmpty(sourceText.transcript());
            case NOTES -> !isEmpty(sourceText.notes());
            case CHAT_WINDOW -> sourceText.chatWindowMessages() != null && !sourceText.chatWindowMessages().isEmpty();
            case DESCRIPTION_ONLY -> !isEmpty(sourceText.description());
            case NONE -> true;
        };
    }

    private String buildUnifiedContent(MeetingSourceTextDto sourceText, MeetingSourceKind sourceUsed) {
        StringBuilder sb = new StringBuilder();
        if (!isEmpty(sourceText.title())) {
            sb.append("Tiêu đề: ").append(sourceText.title()).append("\n");
        }
        if (!isEmpty(sourceText.description())) {
            sb.append("Mô tả: ").append(sourceText.description()).append("\n");
        }

        switch (sourceUsed) {
            case TRANSCRIPT -> {
                if (!isEmpty(sourceText.transcript())) {
                    sb.append("\n[NỘI DUNG BẢN GHI ÂM (TRANSCRIPT)]\n").append(sourceText.transcript());
                }
            }
            case NOTES -> {
                if (!isEmpty(sourceText.notes())) {
                    sb.append("\n[GHI CHÚ CUỘC HỌP (NOTES)]\n").append(sourceText.notes());
                }
            }
            case CHAT_WINDOW -> {
                if (sourceText.chatWindowMessages() != null && !sourceText.chatWindowMessages().isEmpty()) {
                    sb.append("\n[TIN NHẮN TRONG PHÒNG HỌP]\n");
                    for (ChatMessageSummaryDto msg : sourceText.chatWindowMessages()) {
                        sb.append(msg.senderName() != null ? msg.senderName() : "User #" + msg.senderId())
                                .append(": ").append(msg.content()).append("\n");
                    }
                }
            }
            default -> {
                if (!isEmpty(sourceText.notes())) {
                    sb.append("\n[GHI CHÚ]\n").append(sourceText.notes());
                }
                if (!isEmpty(sourceText.transcript())) {
                    sb.append("\n[TRANSCRIPT]\n").append(sourceText.transcript());
                }
            }
        }
        return sb.toString().trim();
    }

    private MeetingSummaryDto generateLlmSummary(
            ChatModel chatModel,
            Long meetingId,
            String title,
            String unifiedContent,
            MeetingSourceKind sourceUsed) {

        String prompt = """
                Bạn là trợ lý AI chuyên tóm tắt các cuộc họp dự án. Hãy đọc nội dung cuộc họp sau và trích xuất thành định dạng JSON hợp lệ:
                {
                  "overview": "Tóm tắt tổng quan súc tích về cuộc họp (2-4 câu)",
                  "keyDecisions": ["Quyết định quan trọng 1", "Quyết định quan trọng 2"],
                  "actionItems": [
                    {
                      "index": 1,
                      "title": "Tiêu đề công việc cần làm ngắn gọn",
                      "description": "Mô tả chi tiết việc cần làm",
                      "assigneeName": "Tên người được giao (hoặc null)",
                      "dueDate": "YYYY-MM-DD (hoặc null)",
                      "priority": "LOW | MEDIUM | HIGH | URGENT",
                      "sourceQuote": "Trích dẫn trực tiếp từ cuộc họp"
                    }
                  ],
                  "openQuestions": ["Câu hỏi còn tồn đọng hoặc chưa được giải quyết"]
                }
                LƯU Ý: Chỉ trả về duy nhất chuỗi JSON hợp lệ, không bọc markdown ```json và không có văn bản giải thích.
                
                Nội dung cuộc họp:
                """ + unifiedContent;

        String response = chatModel.chat(prompt);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("LLM returned empty summary response");
        }

        String cleaned = cleanJsonOutput(response);
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            String overview = root.path("overview").asText("Tóm tắt cuộc họp " + title);
            List<String> keyDecisions = readStringList(root.path("keyDecisions"));
            List<String> openQuestions = readStringList(root.path("openQuestions"));

            List<TaskActionItemDto> actionItems = new ArrayList<>();
            JsonNode itemsNode = root.path("actionItems");
            if (itemsNode.isArray()) {
                int idx = 1;
                for (JsonNode item : itemsNode) {
                    String itemTitle = item.path("title").asText("Nhiệm vụ " + idx);
                    String itemDesc = item.path("description").asText(null);
                    String assignee = item.hasNonNull("assigneeName") ? item.path("assigneeName").asText() : null;
                    String due = item.hasNonNull("dueDate") ? item.path("dueDate").asText() : null;
                    String prio = item.path("priority").asText("MEDIUM").toUpperCase(Locale.ROOT);
                    String quote = item.path("sourceQuote").asText(null);

                    actionItems.add(new TaskActionItemDto(
                            idx++,
                            itemTitle,
                            itemDesc,
                            assignee,
                            null,
                            assignee != null && !assignee.isBlank(),
                            due,
                            prio,
                            quote));
                }
            }

            return new MeetingSummaryDto(
                    meetingId,
                    title,
                    overview,
                    keyDecisions,
                    actionItems,
                    openQuestions,
                    sourceUsed,
                    Instant.now(),
                    false);
        } catch (Exception e) {
            log.error("[MeetingSummarization] Failed to parse LLM response JSON: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public MeetingSummaryDto generateHeuristicSummary(
            Long meetingId,
            String title,
            MeetingSourceTextDto sourceText,
            MeetingSourceKind sourceUsed) {

        String text = buildUnifiedContent(sourceText, sourceUsed);
        String overview = "Tổng hợp nội dung cuộc họp '" + title + "'.";
        List<String> decisions = new ArrayList<>();
        List<TaskActionItemDto> actionItems = new ArrayList<>();
        List<String> questions = new ArrayList<>();

        String[] lines = text.split("\n");
        int actionIndex = 1;

        Pattern actionPattern = Pattern.compile("(?i)(?:todo|cần làm|nhiệm vụ|action item|triển khai|fix|sửa|hoàn thành|giao cho)[:\\-–\\s]+(.+)");
        Pattern decisionPattern = Pattern.compile("(?i)(?:quyết định|thống nhất|chốt|đồng ý|kết luận)[:\\-–\\s]+(.+)");
        Pattern questionPattern = Pattern.compile("(?i)(?:câu hỏi|thắc mắc|chưa rõ|cần làm rõ|vướng mắc)[:\\-–\\s]+(.+)");

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("[") && line.endsWith("]")) {
                continue;
            }

            Matcher dMatcher = decisionPattern.matcher(line);
            if (dMatcher.find()) {
                decisions.add(dMatcher.group(1).trim());
                continue;
            }

            Matcher aMatcher = actionPattern.matcher(line);
            if (aMatcher.find()) {
                String taskText = aMatcher.group(1).trim();
                actionItems.add(new TaskActionItemDto(
                        actionIndex++,
                        taskText.length() > 60 ? taskText.substring(0, 57) + "..." : taskText,
                        taskText,
                        null,
                        null,
                        false,
                        LocalDate.now().plusDays(7).toString(),
                        "MEDIUM",
                        line));
                continue;
            }

            Matcher qMatcher = questionPattern.matcher(line);
            if (qMatcher.find()) {
                questions.add(qMatcher.group(1).trim());
                continue;
            }

            if (line.startsWith("-") || line.startsWith("*")) {
                String bulletContent = line.substring(1).trim();
                if (bulletContent.toLowerCase(Locale.ROOT).contains("quyết định") || bulletContent.toLowerCase(Locale.ROOT).contains("thống nhất")) {
                    decisions.add(bulletContent);
                } else if (bulletContent.toLowerCase(Locale.ROOT).contains("làm") || bulletContent.toLowerCase(Locale.ROOT).contains("task")) {
                    actionItems.add(new TaskActionItemDto(
                            actionIndex++,
                            bulletContent.length() > 60 ? bulletContent.substring(0, 57) + "..." : bulletContent,
                            bulletContent,
                            null,
                            null,
                            false,
                            LocalDate.now().plusDays(7).toString(),
                            "MEDIUM",
                            line));
                }
            }
        }

        if (decisions.isEmpty() && !text.isBlank()) {
            decisions.add("Thống nhất kế hoạch công việc và định hướng triển khai các hạng mục tiếp theo.");
        }

        return new MeetingSummaryDto(
                meetingId,
                title,
                overview,
                decisions,
                actionItems,
                questions,
                sourceUsed,
                Instant.now(),
                false);
    }

    private MeetingSummaryDto parseSavedSummaryJson(String json, Long meetingId, String defaultTitle) {
        try {
            JsonNode root = objectMapper.readTree(cleanJsonOutput(json));
            String title = root.path("title").asText(defaultTitle);
            String overview = root.path("overview").asText("");
            List<String> decisions = readStringList(root.path("keyDecisions"));
            List<String> questions = readStringList(root.path("openQuestions"));
            MeetingSourceKind sourceKind = MeetingSourceKind.valueOf(root.path("sourceUsed").asText("NONE"));
            Instant generatedAt = root.hasNonNull("generatedAt") ? Instant.parse(root.path("generatedAt").asText()) : Instant.now();

            List<TaskActionItemDto> items = new ArrayList<>();
            JsonNode itemsNode = root.path("actionItems");
            if (itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    items.add(new TaskActionItemDto(
                            item.path("index").asInt(1),
                            item.path("title").asText("Task"),
                            item.path("description").asText(null),
                            item.path("assigneeName").asText(null),
                            item.hasNonNull("assigneeId") ? item.path("assigneeId").asLong() : null,
                            item.path("assigneeUnresolved").asBoolean(false),
                            item.path("dueDate").asText(null),
                            item.path("priority").asText("MEDIUM"),
                            item.path("sourceQuote").asText(null)));
                }
            }

            return new MeetingSummaryDto(
                    meetingId,
                    title,
                    overview,
                    decisions,
                    items,
                    questions,
                    sourceKind,
                    generatedAt,
                    true);
        } catch (Exception e) {
            log.warn("[MeetingSummarization] Could not parse saved summary JSON: {}", e.getMessage());
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }

    private String cleanJsonOutput(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private boolean isEmpty(String str) {
        return str == null || str.isBlank();
    }
}
