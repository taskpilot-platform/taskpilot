package com.taskpilot.contracts.workspace.chat;

import java.time.Instant;
import java.util.List;

public interface ProjectChatPort {

    List<ChatMessageSummaryDto> getRecentMessages(Long projectId, Long beforeMessageId, int limit, Long requesterUserId);

    List<ChatMessageSummaryDto> searchMessages(Long projectId, ChatSearchCriteria criteria, Long requesterUserId);

    List<ChatMessageSummaryDto> getMessagesBetween(Long projectId, Instant from, Instant to, int limit, Long requesterUserId);

    boolean canAnnounce(Long projectId, Long requesterUserId);

    ChatMessageSummaryDto postAnnouncement(Long projectId, String content, Long requesterUserId);
}
