package com.taskpilot.infrastructure.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;

public interface StorageService {
    String uploadFile(MultipartFile file, String folder) throws IOException;
    void deleteFile(String fileUrl);
    InputStream downloadFile(String fileUrlOrKey) throws IOException;
}
