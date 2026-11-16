package com.taskpilot.infrastructure.storage;

import com.taskpilot.infrastructure.storage.range.StorageDeleteResult;
import com.taskpilot.infrastructure.storage.range.StorageObjectStat;
import com.taskpilot.infrastructure.storage.range.StorageReadRequest;
import com.taskpilot.infrastructure.storage.range.StorageReadResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

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
     * Plan a deterministic object key before executing a provider write.
     */
    default String planObjectKey(String folder, String originalFilename) {
        String sanitizedFilename = (originalFilename != null && !originalFilename.isBlank())
                ? originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "file";
        String prefix = (folder != null && !folder.isEmpty())
                ? (folder.endsWith("/") ? folder : folder + "/")
                : "";
        return prefix + UUID.randomUUID() + "-" + sanitizedFilename;
    }

    /**
     * Upload a file to an exact predetermined object key.
     */
    default String uploadFileAtKey(MultipartFile file, String bucket, String key) throws IOException {
        return uploadFile(file, "", bucket);
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
     * Stream an inclusive byte range [start, end] from an explicit bucket.
     */
    default InputStream downloadFileRange(String bucket, String key, long start, long end) throws IOException {
        return downloadFile(bucket, key);
    }

    /**
     * Stream an object (optionally a single exact byte range) from an explicit bucket.
     */
    default StorageReadResult readObject(StorageReadRequest request) throws IOException {
        InputStream stream = downloadFile(request.bucket(), request.key());
        return new StorageReadResult(stream, -1L, -1L, null, request.range(), Optional.empty());
    }

    /**
     * Read lightweight object metadata without downloading the object body.
     */
    default Optional<StorageObjectStat> statObject(String bucket, String key) throws IOException {
        return Optional.empty();
    }

    /**
     * Delete an object from an explicit bucket.
     *
     * @param bucket explicit target bucket name (e.g., "documents")
     * @param key the storage object key
     */
    void deleteFile(String bucket, String key);

    /**
     * Delete an object and propagate structured outcome (404 treated as idempotent success).
     */
    default StorageDeleteResult deleteObject(String bucket, String key) {
        deleteFile(bucket, key);
        return StorageDeleteResult.deleted();
    }

    /**
     * Backward-compatible delete defaulting to documents bucket.
     */
    default void deleteFile(String fileUrlOrKey) {
        deleteFile("documents", fileUrlOrKey);
    }
}
