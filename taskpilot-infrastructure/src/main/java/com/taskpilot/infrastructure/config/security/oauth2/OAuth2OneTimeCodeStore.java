package com.taskpilot.infrastructure.config.security.oauth2;

import java.util.Optional;

public interface OAuth2OneTimeCodeStore {

    /**
     * Sinh và lưu mã trao đổi một lần liên kết với userId.
     * 
     * @param userId Định danh người dùng
     * @return Mã URL-safe dùng một lần
     */
    String generateAndStore(Long userId);

    /**
     * Tiêu thụ nguyên tử mã trao đổi (chỉ dùng được một lần).
     * 
     * @param code Mã trao đổi
     * @return Optional chứa userId nếu hợp lệ và chưa hết hạn, rỗng nếu không hợp lệ hoặc đã dùng
     */
    Optional<Long> consume(String code);
}
