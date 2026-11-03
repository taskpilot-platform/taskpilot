package com.taskpilot.contracts.workspace.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileCategoryTest {

    @ParameterizedTest(name = "[{index}] raw=\"{0}\" -> {1}")
    @CsvSource({
        "document, DOCUMENT",
        "doc, DOCUMENT",
        "tài liệu, DOCUMENT",
        "tai lieu, DOCUMENT",
        "văn bản, DOCUMENT",
        "pdf, DOCUMENT",
        "word, DOCUMENT",
        "spreadsheet, SPREADSHEET",
        "bảng tính, SPREADSHEET",
        "bang tinh, SPREADSHEET",
        "excel, SPREADSHEET",
        "csv, SPREADSHEET",
        "presentation, PRESENTATION",
        "trình chiếu, PRESENTATION",
        "trinh chieu, PRESENTATION",
        "pptx, PRESENTATION",
        "slide, PRESENTATION",
        "image, IMAGE",
        "hình ảnh, IMAGE",
        "hinh anh, IMAGE",
        "ảnh, IMAGE",
        "video, VIDEO",
        "phim, VIDEO",
        "audio, AUDIO",
        "âm thanh, AUDIO",
        "am thanh, AUDIO",
        "archive, ARCHIVE",
        "tệp nén, ARCHIVE",
        "tep nen, ARCHIVE",
        "zip, ARCHIVE",
        "code, CODE",
        "mã nguồn, CODE",
        "ma nguon, CODE",
        "recording, RECORDING",
        "bản ghi, RECORDING",
        "ban ghi, RECORDING",
        "cuộc họp, RECORDING",
        "cuoc hop, RECORDING",
        "other, OTHER",
        "khác, OTHER",
        "khac, OTHER"
    })
    @DisplayName("Should parse standard English and Vietnamese category aliases")
    void shouldParseCategoryAliases(String raw, FileCategory expected) {
        assertEquals(expected, FileCategory.fromParam(raw));
    }

    @Test
    @DisplayName("Should return null for blank or unknown inputs")
    void shouldReturnNullForBlankOrUnknown() {
        assertNull(FileCategory.fromParam(null));
        assertNull(FileCategory.fromParam(""));
        assertNull(FileCategory.fromParam("   "));
        assertNull(FileCategory.fromParam("random_unknown_category_xyz"));
    }
}
