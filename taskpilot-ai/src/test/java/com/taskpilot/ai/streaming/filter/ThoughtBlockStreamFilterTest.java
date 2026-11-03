package com.taskpilot.ai.streaming.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThoughtBlockStreamFilterTest {

    private ThoughtBlockStreamFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ThoughtBlockStreamFilter();
    }

    @Test
    @DisplayName("1. Plain text with no thought tags streams unchanged")
    void shouldStreamPlainTextUnchanged() {
        String chunk1 = filter.accept("Xin chào bạn! ");
        String chunk2 = filter.accept("Tôi là TaskPilot AI.");
        String flushed = filter.flush();

        assertEquals("Xin chào bạn! ", chunk1);
        assertEquals("Tôi là TaskPilot AI.", chunk2);
        assertEquals("", flushed);
        assertEquals("Xin chào bạn! Tôi là TaskPilot AI.", filter.getVisibleResponse());
        assertEquals("", filter.getHiddenReasoning());
    }

    @Test
    @DisplayName("2. Complete thought block in single chunk is filtered")
    void shouldFilterCompleteThoughtBlock() {
        String result = filter.accept("<thought>Tôi cần gọi tool để tìm task.</thought>Chào bạn, tôi đang tìm task.");
        filter.flush();

        assertEquals("Chào bạn, tôi đang tìm task.", result);
        assertEquals("Tôi cần gọi tool để tìm task.", filter.getHiddenReasoning().trim());
        assertEquals("Chào bạn, tôi đang tìm task.", filter.getVisibleResponse());
    }

    @Test
    @DisplayName("3. Split opening tag across chunks is properly buffered and stripped")
    void shouldHandleSplitOpeningTag() {
        String c1 = filter.accept("Trước <tho");
        assertEquals("Trước ", c1);

        String c2 = filter.accept("ught>Đây là suy nghĩ ngầm</thought>Sau");
        filter.flush();

        assertEquals("Sau", c2);
        assertEquals("Trước Sau", filter.getVisibleResponse());
        assertEquals("Đây là suy nghĩ ngầm", filter.getHiddenReasoning().trim());
    }

    @Test
    @DisplayName("4. Split closing tag across chunks is properly handled")
    void shouldHandleSplitClosingTag() {
        filter.accept("<thought>Suy nghĩ rất dài </th");
        filter.accept("ought>Nội dung hiển thị");
        filter.flush();

        assertEquals("Nội dung hiển thị", filter.getVisibleResponse());
        assertEquals("Suy nghĩ rất dài ", filter.getHiddenReasoning());
    }

    @Test
    @DisplayName("5. Case-insensitivity for <THOUGHT>, <Think>, and <DTHINK>")
    void shouldHandleCaseInsensitiveTags() {
        filter.accept("<THOUGHT>R1</THOUGHT>A");
        filter.accept("<Think>R2</Think>B");
        filter.accept("<DTHINK>R3</DTHINK>C");
        filter.flush();

        assertEquals("ABC", filter.getVisibleResponse());
        assertTrue(filter.getHiddenReasoning().contains("R1"));
        assertTrue(filter.getHiddenReasoning().contains("R2"));
        assertTrue(filter.getHiddenReasoning().contains("R3"));
    }

    @Test
    @DisplayName("6. Tags with attributes are parsed and suppressed")
    void shouldHandleTagsWithAttributes() {
        filter.accept("<thought level=\"deep\" provider=\"gemma\">Phân tích ngữ cảnh</thought>Đã xong.");
        filter.flush();

        assertEquals("Đã xong.", filter.getVisibleResponse());
        assertEquals("Phân tích ngữ cảnh", filter.getHiddenReasoning().trim());
    }

    @Test
    @DisplayName("7. Non-tag '<' like comparison operators are preserved")
    void shouldPreserveMathematicalInequalities() {
        String r1 = filter.accept("Nếu số task < 5 và hoàn thành > 2 thì tốt.");
        String r2 = filter.flush();

        assertEquals("Nếu số task < 5 và hoàn thành > 2 thì tốt.", r1 + r2);
        assertEquals("", filter.getHiddenReasoning());
    }

    @Test
    @DisplayName("8. Standard HTML tags are not stripped")
    void shouldPreserveStandardHtmlTags() {
        String r = filter.accept("Dòng 1<br/>Dòng 2 <b>in đậm</b>");
        filter.flush();

        assertEquals("Dòng 1<br/>Dòng 2 <b>in đậm</b>", r);
    }

    @Test
    @DisplayName("9. TaskPilot Form blocks are preserved intact")
    void shouldPreserveTaskpilotFormBlock() {
        String form = ":::taskpilot-form\n{\"actionId\":\"act-123\"}\n:::";
        String r = filter.accept(form);
        filter.flush();

        assertEquals(form, r);
    }

    @Test
    @DisplayName("10. Unclosed thought block does not leak to visible response")
    void shouldNotLeakUnclosedThought() {
        filter.accept("<thought>Mô hình bị ngắt kết nối giữa chừng");
        filter.flush();

        assertEquals("", filter.getVisibleResponse());
        assertTrue(filter.isInsideThought());
        assertTrue(filter.getHiddenReasoning().contains("ngắt kết nối"));
    }

    @Test
    @DisplayName("11. Stream with only thought yields empty visible response")
    void shouldYieldEmptyWhenOnlyThought() {
        filter.accept("<thought>Chỉ có reasoning không có câu trả lời.</thought>");
        filter.flush();

        assertEquals("", filter.getVisibleResponse());
        assertFalse(filter.getHiddenReasoning().isEmpty());
    }

    @Test
    @DisplayName("12. Character-by-character chunk streaming")
    void shouldHandleCharacterByCharacterStreaming() {
        String input = "<thought>Suy nghĩ</thought>Chào bạn!";
        StringBuilder visible = new StringBuilder();

        for (char c : input.toCharArray()) {
            visible.append(filter.accept(String.valueOf(c)));
        }
        visible.append(filter.flush());

        assertEquals("Chào bạn!", visible.toString());
        assertEquals("Suy nghĩ", filter.getHiddenReasoning());
    }

    @Test
    @DisplayName("13. Multiple sequential thought blocks in one stream")
    void shouldHandleMultipleThoughtBlocks() {
        filter.accept("<thought>Ý 1</thought>Phần 1. <thought>Ý 2</thought>Phần 2.");
        filter.flush();

        assertEquals("Phần 1. Phần 2.", filter.getVisibleResponse());
    }

    @Test
    @DisplayName("14. Whitespace inside tags")
    void shouldHandleWhitespaceInsideTag() {
        filter.accept("<  thought  >Lập luận nội bộ< / thought >Xong");
        filter.flush();

        assertEquals("Xong", filter.getVisibleResponse());
        assertEquals("Lập luận nội bộ", filter.getHiddenReasoning().trim());
    }

    @Test
    @DisplayName("15. Null and empty chunks return empty string without error")
    void shouldHandleNullAndEmptyChunks() {
        assertEquals("", filter.accept(null));
        assertEquals("", filter.accept(""));
        assertEquals("", filter.flush());
    }
}
