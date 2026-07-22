package com.taskpilot.ai.gatekeeper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GatekeeperService {

    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "phân công", "phan cong", "giao việc", "giao task", "giao viec",
            "chia viec", "chia việc", "chọn ai", "chon ai", "chọn người",
            "chon nguoi", "tìm người", "tim nguoi", "ai ranh", "ai rảnh",
            "ung vien", "ứng viên", "assign", "candidate", "chia task",
            "goi y", "gợi ý", "de xuat", "đề xuất", "recommend"
    );

    // Dạng câu phức tạp hơn (VD: team nào rảnh, ai phù hợp, chọn ... người cho)
    private static final List<Pattern> AHP_PATTERNS = List.of(
            Pattern.compile(".*(team|nhóm|người).*rảnh.*"),
            Pattern.compile(".*ai.*phù hợp.*"),
            Pattern.compile(".*ai.*phu hop.*"),
            Pattern.compile(".*chọn.*(người|nhân viên|thành viên).*cho.*"),
            Pattern.compile(".*chon.*(nguoi|nhan vien|thanh vien).*cho.*"),
            Pattern.compile(".*mống nào.*"),
            Pattern.compile(".*bơi vào.*"),
            Pattern.compile(".*vớt cái này.*")
    );

    // Negative patterns (bypass AHP)
    private static final List<Pattern> NEGATIVE_PATTERNS = List.of(
            Pattern.compile(".*ai đang làm.*"),
            Pattern.compile(".*ai dang lam.*"),
            Pattern.compile(".*tiến độ.*"),
            Pattern.compile(".*tien do.*"),
            Pattern.compile(".*tình trạng.*"),
            Pattern.compile(".*tinh trang.*")
    );

    @PostConstruct
    void init() {
        log.info("[Gatekeeper] Initialized static code-based gatekeeper.");
    }

    public boolean requiresAHP(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }

        boolean requiresAHP = classifyByRules(userMessage);
        log.info("[Gatekeeper] requiresAHP={} (code-based rules)", requiresAHP);
        return requiresAHP;
    }

    private boolean classifyByRules(String userMessage) {
        String normalized = userMessage.toLowerCase(Locale.ROOT);

        // 1. Check negative patterns (if matched, it's just a read query)
        for (Pattern pattern : NEGATIVE_PATTERNS) {
            if (pattern.matcher(normalized).matches()) {
                return false;
            }
        }

        // 2. Check context-aware task ID + assignment verb (simplified check for task assignment)
        if (normalized.matches(".*(task|cv|công việc)\\s+#?\\d+.*") && 
            (normalized.contains("giao") || normalized.contains("assign") || normalized.contains("chia"))) {
            return true;
        }

        // 3. Check regex patterns
        for (Pattern pattern : AHP_PATTERNS) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }

        // 4. Check keywords
        for (String keyword : FALLBACK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }

        return false;
    }
}
