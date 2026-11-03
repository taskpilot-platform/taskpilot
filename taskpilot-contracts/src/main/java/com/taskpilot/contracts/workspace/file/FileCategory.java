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

    public static FileCategory fromMimeAndName(String mimeType, String fileName) {
        if (fileName != null) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".rtf") || lower.endsWith(".odt") || lower.endsWith(".md")) return DOCUMENT;
            if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".ods")) return SPREADSHEET;
            if (lower.endsWith(".ppt") || lower.endsWith(".pptx") || lower.endsWith(".odp")) return PRESENTATION;
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp") || lower.endsWith(".bmp")) return IMAGE;
            if (lower.endsWith(".webm") || lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".mkv")) {
                if (lower.contains("meeting") || lower.contains("hop") || lower.contains("record")) return RECORDING;
                return VIDEO;
            }
            if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".flac")) return AUDIO;
            if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")) return ARCHIVE;
            if (lower.endsWith(".java") || lower.endsWith(".ts") || lower.endsWith(".js") || lower.endsWith(".py") || lower.endsWith(".sql") || lower.endsWith(".html") || lower.endsWith(".css")) return CODE;
        }
        if (mimeType != null) {
            String lower = mimeType.toLowerCase(Locale.ROOT);
            if (lower.contains("pdf") || lower.contains("word") || lower.contains("text/plain") || lower.contains("document")) return DOCUMENT;
            if (lower.contains("sheet") || lower.contains("excel") || lower.contains("csv")) return SPREADSHEET;
            if (lower.contains("presentation") || lower.contains("powerpoint")) return PRESENTATION;
            if (lower.startsWith("image/")) return IMAGE;
            if (lower.startsWith("video/")) return VIDEO;
            if (lower.startsWith("audio/")) return AUDIO;
            if (lower.contains("zip") || lower.contains("tar") || lower.contains("compressed")) return ARCHIVE;
            if (lower.contains("json") || lower.contains("javascript") || lower.contains("xml")) return CODE;
        }
        return OTHER;
    }

    private static String stripAccents(String s) {
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").replace("đ", "d").replace("Đ", "D");
    }
}
