package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleDriveLiveCheckTest {

    private Map<String, String> loadEnv() {
        Map<String, String> env = new HashMap<>();
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            envPath = Path.of("../.env");
        }
        if (Files.exists(envPath)) {
            try {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    line = line.trim();
                    if (!line.startsWith("#") && line.contains("=")) {
                        int eq = line.indexOf('=');
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                            v = v.substring(1, v.length() - 1);
                        }
                        env.put(k, v);
                    }
                }
            } catch (Exception ignored) {}
        }
        return env;
    }

    @Test
    void testGoogleDriveUploadConnectivity() throws Exception {
        Map<String, String> env = loadEnv();

        String clientId = env.getOrDefault("GOOGLE_DRIVE_CLIENT_ID", System.getenv("GOOGLE_DRIVE_CLIENT_ID"));
        String clientSecret = env.getOrDefault("GOOGLE_DRIVE_CLIENT_SECRET", System.getenv("GOOGLE_DRIVE_CLIENT_SECRET"));
        String refreshToken = env.getOrDefault("GOOGLE_DRIVE_REFRESH_TOKEN", System.getenv("GOOGLE_DRIVE_REFRESH_TOKEN"));
        String folderId = env.getOrDefault("GOOGLE_DRIVE_FOLDER_ID", System.getenv("GOOGLE_DRIVE_FOLDER_ID"));

        System.out.println("Testing Google Drive with Folder ID: " + folderId);
        System.out.println("Client ID starts with: " + (clientId != null ? clientId.substring(0, Math.min(10, clientId.length())) : "null"));

        assertThat(clientId).isNotBlank();
        assertThat(clientSecret).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(folderId).isNotBlank();

        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId.trim())
                .setClientSecret(clientSecret.trim())
                .setRefreshToken(refreshToken.trim())
                .build();

        Drive drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("TaskPilot")
                .build();

        GoogleDriveService service = new GoogleDriveService(drive, folderId.trim());

        byte[] testContent = ("TaskPilot Google Drive 5TB Integration Test - " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "taskpilot-live-check-" + System.currentTimeMillis() + ".txt",
                "text/plain",
                testContent
        );

        GoogleDriveFileDto result = service.uploadFile(file);

        System.out.println("SUCCESS: Uploaded file to Google Drive 5TB!");
        System.out.println("File ID: " + result.getFileId());
        System.out.println("File Name: " + result.getName());
        System.out.println("Web View Link: " + result.getWebViewLink());
        System.out.println("Web Content Link: " + result.getWebContentLink());

        assertThat(result.getFileId()).isNotBlank();
        assertThat(result.getWebViewLink()).isNotBlank();
    }
}
