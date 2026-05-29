package com.taskpilot.infrastructure.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

public interface StorageService {
    String uploadFile(MultipartFile file, String folder) throws IOException;
    void deleteFile(String fileUrl);
}
