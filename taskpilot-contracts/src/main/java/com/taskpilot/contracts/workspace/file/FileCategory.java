package com.taskpilot.contracts.workspace.file;

import java.text.Normalizer;
import java.util.Locale;

public enum FileCategory {
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    CODE,
    RECORDING,
    OTHER;

    public static FileCategory fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = stripAccents(raw).toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "document", "doc", "tai lieu", "van ban", "pdf", "word" -> DOCUMENT;
            case "spreadsheet", "sheet", "bang tinh", "excel", "csv" -> SPREADSHEET;
            case "presentation", "slide", "trinh chieu", "powerpoint", "pptx" -> PRESENTATION;
            case "image", "anh", "hinh anh", "photo", "picture" -> IMAGE;
            case "video", "clip", "phim" -> VIDEO;
            case "audio", "am thanh", "voice", "mp3" -> AUDIO;
            case "archive", "nen", "tep nen", "zip", "rar", "tar" -> ARCHIVE;
            case "code", "ma nguon", "source code", "script" -> CODE;
            case "recording", "ban ghi", "ghi hinh", "cuoc hop", "meeting recording" -> RECORDING;
            case "other", "khac" -> OTHER;
            default -> {
                for (FileCategory cat : values()) {
                    if (cat.name().equalsIgnoreCase(normalized)) {
                        yield cat;
                    }
                }
                yield null;
            }
        };
    }

    private static String stripAccents(String s) {
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").replace("đ", "d").replace("Đ", "D");
    }
}
