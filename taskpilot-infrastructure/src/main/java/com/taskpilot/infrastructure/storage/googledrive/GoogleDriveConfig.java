package com.taskpilot.infrastructure.storage.googledrive;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;

@Slf4j
@Configuration
public class GoogleDriveConfig {

    @Value("${google.drive.client-id:}")
    private String clientId;

    @Value("${google.drive.client-secret:}")
    private String clientSecret;

    @Value("${google.drive.refresh-token:}")
    private String refreshToken;

    @Bean
    public Drive googleDrive() throws GeneralSecurityException, IOException {
        if (clientId == null || clientId.isBlank() || refreshToken == null || refreshToken.isBlank()) {
            log.warn("Google Drive credentials not configured. Google Drive client may fail if invoked.");
        }

        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId != null ? clientId.trim() : "")
                .setClientSecret(clientSecret != null ? clientSecret.trim() : "")
                .setRefreshToken(refreshToken != null ? refreshToken.trim() : "")
                .build();

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("TaskPilot")
                .build();
    }
}
