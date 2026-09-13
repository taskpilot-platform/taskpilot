package com.taskpilot.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;

/**
 * Emits the runtime artifact fingerprint at startup (Step 6).
 * Distinguishes source code vs built JAR vs running process.
 */
@Slf4j
@Component
public class BuildFingerprintLogger {

    private final BuildProperties buildProperties;
    private final GitProperties gitProperties;

    @Autowired
    public BuildFingerprintLogger(
            @Autowired(required = false) BuildProperties buildProperties,
            @Autowired(required = false) GitProperties gitProperties) {
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logBuildFingerprint() {
        String version = resolveVersion();
        String commit = resolveCommit();
        String timestamp = resolveTimestamp();

        log.info("""
                
                ============================================================
                TaskPilot build:
                version={}
                commit={}
                timestamp={}
                ============================================================""",
                version, commit, timestamp);
    }

    private String resolveVersion() {
        if (buildProperties != null && buildProperties.getVersion() != null) {
            return buildProperties.getVersion();
        }
        Properties props = loadClasspathProperties("/META-INF/build-info.properties");
        if (props.containsKey("build.version")) {
            return props.getProperty("build.version");
        }
        String pkgVersion = getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : null;
        return pkgVersion != null ? pkgVersion : "0.0.1-SNAPSHOT";
    }

    private String resolveCommit() {
        if (gitProperties != null && gitProperties.getShortCommitId() != null) {
            return gitProperties.getShortCommitId();
        }
        Properties props = loadClasspathProperties("/git.properties");
        if (props.containsKey("git.commit.id.abbrev")) {
            return props.getProperty("git.commit.id.abbrev");
        }
        if (props.containsKey("git.commit.id")) {
            String fullId = props.getProperty("git.commit.id");
            return fullId.length() >= 7 ? fullId.substring(0, 7) : fullId;
        }
        return "unknown";
    }

    private String resolveTimestamp() {
        if (buildProperties != null && buildProperties.getTime() != null) {
            return buildProperties.getTime().toString();
        }
        Properties buildProps = loadClasspathProperties("/META-INF/build-info.properties");
        if (buildProps.containsKey("build.time")) {
            return buildProps.getProperty("build.time");
        }
        Properties gitProps = loadClasspathProperties("/git.properties");
        if (gitProps.containsKey("git.build.time")) {
            return gitProps.getProperty("git.build.time");
        }
        return Instant.now().toString();
    }

    private Properties loadClasspathProperties(String path) {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (Exception ignored) {
        }
        return properties;
    }
}
