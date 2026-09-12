package com.taskpilot.infrastructure.storage;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;

public interface StorageService {

    /**
     * Upload a file into a specific bucket and folder.
     *
     * @param file the multipart file to upload
     * @param folder folder prefix (e.g., "projects/1/documents" or "avatars")
     * @param bucket explicit target bucket name (e.g., "documents" or "avatars")
     * @return the storage key (or public URL if the bucket is public like avatars)
     * @throws IOException on I/O failure
     */
    String uploadFile(MultipartFile file, String folder, String bucket) throws IOException;

    /**
     * Backward-compatible upload defaulting to avatars bucket.
     */
    default String uploadFile(MultipartFile file, String folder) throws IOException {
        return uploadFile(file, folder, "avatars");
    }

    /**
     * Download a file stream from an explicit bucket.
     *
     * @param bucket explicit target bucket name (e.g., "documents")
     * @param key the storage object key
     * @return input stream of the file content
     * @throws IOException on failure
     */
    InputStream downloadFile(String bucket, String key) throws IOException;

    /**
     * Backward-compatible download defaulting to documents bucket.
     */
    default InputStream downloadFile(String fileUrlOrKey) throws IOException {
        return downloadFile("documents", fileUrlOrKey);
    }

    /**
     * Delete an object from an explicit bucket.
     *
     * @param bucket explicit target bucket name (e.g., "documents")
     * @param key the storage object key
     */
    void deleteFile(String bucket, String key);

    /**
     * Backward-compatible delete defaulting to documents bucket.
     */
    default void deleteFile(String fileUrlOrKey) {
        deleteFile("documents", fileUrlOrKey);
    }
}
