package com.taskpilot.ai.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageSanitizerCurrentRequestTest {

    private final ChatMessageSanitizer sanitizer = new ChatMessageSanitizer();

    @Test
    void keepsCurrentUserRequestContainingXacNhanAcrossToolRounds() {
        String currentPrompt = "Trong dự án ID 3, hãy đề xuất người phù hợp nhất cho công việc ID 6 rồi chuẩn bị giao việc để tôi xác nhận.";
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("c1").name("queryTasks").arguments("{\"projectId\":3}").build();
        List<ChatMessage> history = List.of(
                SystemMessage.from("System prompt"),
                UserMessage.from(currentPrompt),
                AiMessage.from(req),
                ToolExecutionResultMessage.from(req, "[{\"id\":6,\"title\":\"Task 6\"}]")
        );

        List<ChatMessage> sanitized = sanitizer.sanitizeHistoryForTools(history);

        List<String> userTexts = sanitized.stream()
                .filter(UserMessage.class::isInstance)
                .map(m -> ((UserMessage) m).singleText())
                .toList();
        assertThat(userTexts).containsExactly(currentPrompt);

        int userIdx = -1;
        int toolResultIdx = -1;
        for (int i = 0; i < sanitized.size(); i++) {
            if (sanitized.get(i) instanceof UserMessage u && currentPrompt.equals(u.singleText())) userIdx = i;
            if (sanitized.get(i) instanceof SystemMessage s && s.text().contains("SYSTEM TOOL RESULT [queryTasks]")) toolResultIdx = i;
        }
        assertThat(userIdx).isGreaterThan(0);
        assertThat(toolResultIdx).isGreaterThan(userIdx);
    }

    @Test
    void keepsCurrentUserRequestContainingHuyBoWhileStillDroppingPastConfirmCancelTurns() {
        String currentPrompt = "Giao task 5 cho An, nếu sai thì hủy bỏ và tôi xác nhận sau.";
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("c2").name("queryTasks").arguments("{}").build();
        List<ChatMessage> history = List.of(
                SystemMessage.from("System prompt"),
                UserMessage.from("xác nhận"),
                AiMessage.from("Đã xác nhận giao task trước đó."),
                UserMessage.from("thôi hủy"),
                AiMessage.from("Đã hủy thao tác."),
                UserMessage.from(currentPrompt),
                AiMessage.from(req),
                ToolExecutionResultMessage.from(req, "[]")
        );

        List<ChatMessage> sanitized = sanitizer.sanitizeHistoryForTools(history);

        List<String> userTexts = sanitized.stream()
                .filter(UserMessage.class::isInstance)
                .map(m -> ((UserMessage) m).singleText())
                .toList();
        assertThat(userTexts).containsExactly(currentPrompt);

        List<String> aiTexts = sanitized.stream()
                .filter(AiMessage.class::isInstance)
                .map(m -> ((AiMessage) m).text())
                .toList();
        assertThat(aiTexts).noneMatch(t -> t.contains("Đã xác nhận") || t.contains("Đã hủy"));
    }

    @Test
    void retainsCurrentUserRequestEvenWhenToolRoundsPushItOutsideEightMessageWindow() {
        String currentPrompt = "Đề xuất và giao task 6 để tôi xác nhận.";
        List<ChatMessage> history = new ArrayList<>();
        history.add(SystemMessage.from("System prompt"));
        history.add(UserMessage.from(currentPrompt));
        for (int round = 0; round < 3; round++) {
            ToolExecutionRequest r1 = ToolExecutionRequest.builder().id("r" + round + "a").name("queryTasks").arguments("{}").build();
            ToolExecutionRequest r2 = ToolExecutionRequest.builder().id("r" + round + "b").name("queryMembers").arguments("{}").build();
            ToolExecutionRequest r3 = ToolExecutionRequest.builder().id("r" + round + "c").name("getTaskDetails").arguments("{}").build();
            history.add(AiMessage.from(List.of(r1, r2, r3)));
            history.add(ToolExecutionResultMessage.from(r1, "res-a"));
            history.add(ToolExecutionResultMessage.from(r2, "res-b"));
            history.add(ToolExecutionResultMessage.from(r3, "res-c"));
        }

        List<ChatMessage> sanitized = sanitizer.sanitizeHistoryForTools(history);

        assertThat(sanitized.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(sanitized.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) sanitized.get(1)).singleText()).isEqualTo(currentPrompt);
    }
}
