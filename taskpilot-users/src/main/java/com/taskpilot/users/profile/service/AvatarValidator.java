package com.taskpilot.users.profile.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Validates uploaded avatar binaries (max 1,000,000 bytes, PNG/JPEG/WebP magic-byte + MIME parity,
 * rejects SVG/HTML/polyglot payloads) and enforces an explicit origin allowlist for avatar URLs.
 */
@Component
public class AvatarValidator {

    public static final int MAX_AVATAR_BYTES = 1_000_000;

    private static final Set<String> PERMITTED_PROVIDER_HOSTS = Set.of(
            "lh3.googleusercontent.com",
            "avatars.githubusercontent.com"
    );

    private static final String PLACEHOLDER_PREFIX = "https://xxxx.supabase.co";

    private final String configuredPublicAvatarPrefix;

    public AvatarValidator() {
        this("");
    }

    @Autowired
    public AvatarValidator(@Value("${supabase.s3.public-url:}") String configuredPublicAvatarPrefix) {
        this.configuredPublicAvatarPrefix = configuredPublicAvatarPrefix != null
                ? configuredPublicAvatarPrefix.trim()
                : "";
    }

    public String validateUploadedImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Avatar file must be non-empty and at most 1,000,000 bytes");
        }
        String claimedMime = file.getContentType() != null
                ? file.getContentType().trim().toLowerCase(Locale.ROOT)
                : "";
        if (!Set.of("image/png", "image/jpeg", "image/jpg", "image/webp").contains(claimedMime)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Only PNG, JPEG, and WebP images are permitted");
        }

        byte[] bytes = readBoundedBytes(file);
        if (bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Avatar file must be non-empty and at most 1,000,000 bytes");
        }

        rejectMarkupPolyglotMarkers(bytes);

        ImageFormat detected = detectFormat(bytes);
        if (detected == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Unsupported or malformed image signature");
        }
        if (!matchesMime(detected, claimedMime)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Image content does not match declared MIME type");
        }
        verifyDecodable(detected, bytes);
        return detected.canonicalMime;
    }

    public String validateAndNormalizeProfileAvatarUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (!isPermittedAvatarUrl(trimmed)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "Avatar URL must originate from an allowed upload storage prefix or permitted identity provider");
        }
        return trimmed;
    }

    public String sanitizeProviderAvatarUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        return isPermittedAvatarUrl(trimmed) ? trimmed : null;
    }

    public boolean isPermittedAvatarUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        if (host.isBlank() || uri.getUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        String path = uri.getPath() != null ? uri.getPath().toLowerCase(Locale.ROOT) : "";
        if (path.endsWith(".svg") || path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".xml")) {
            return false;
        }

        if ("https".equals(scheme) && PERMITTED_PROVIDER_HOSTS.contains(host)) {
            return true;
        }

        if (!configuredPublicAvatarPrefix.isBlank() && !configuredPublicAvatarPrefix.startsWith(PLACEHOLDER_PREFIX)) {
            URI configuredUri;
            try {
                configuredUri = URI.create(configuredPublicAvatarPrefix);
            } catch (IllegalArgumentException ex) {
                return false;
            }
            String cfgScheme = configuredUri.getScheme() != null ? configuredUri.getScheme().toLowerCase(Locale.ROOT) : "";
            String cfgHost = configuredUri.getHost() != null ? configuredUri.getHost().toLowerCase(Locale.ROOT) : "";
            if (("https".equals(cfgScheme) || isLoopbackDevPrefix(cfgScheme, cfgHost))
                    && cfgScheme.equals(scheme)
                    && cfgHost.equals(host)
                    && configuredUri.getPort() == uri.getPort()) {
                String cfgPath = configuredUri.getPath() != null ? configuredUri.getPath() : "";
                String normalizedCfgPath = cfgPath.endsWith("/") ? cfgPath : cfgPath + "/";
                String actualPath = uri.getPath() != null ? uri.getPath() : "";
                return actualPath.startsWith(normalizedCfgPath);
            }
        }
        return false;
    }

    private static boolean isLoopbackDevPrefix(String scheme, String host) {
        return "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host));
    }

    private static byte[] readBoundedBytes(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buffer = in.readNBytes(MAX_AVATAR_BYTES + 1);
            if (buffer.length > MAX_AVATAR_BYTES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                        "Avatar file must be non-empty and at most 1,000,000 bytes");
            }
            return buffer;
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Unable to read uploaded avatar");
        }
    }

    private static void rejectMarkupPolyglotMarkers(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (head.contains("<svg") || head.contains("<html") || head.contains("<!doctype")
                || head.contains("<script") || head.contains("<?xml")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                    "SVG and HTML payloads are not permitted");
        }
    }

    private static ImageFormat detectFormat(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return ImageFormat.PNG;
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return ImageFormat.JPEG;
        }
        if (bytes.length >= 21
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            String chunk = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
            if ("VP8 ".equals(chunk) || "VP8L".equals(chunk) || "VP8X".equals(chunk)) {
                return ImageFormat.WEBP;
            }
        }
        return null;
    }

    private static boolean matchesMime(ImageFormat format, String claimedMime) {
        return switch (format) {
            case PNG -> "image/png".equals(claimedMime);
            case JPEG -> "image/jpeg".equals(claimedMime) || "image/jpg".equals(claimedMime);
            case WEBP -> "image/webp".equals(claimedMime);
        };
    }

    private static void verifyDecodable(ImageFormat format, byte[] bytes) {
        if (format == ImageFormat.WEBP) {
            // JDK ImageIO does not include a WebP reader by default; RIFF/WEBP/VP8 container validation suffices.
            return;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Corrupt or invalid image content");
            }
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Corrupt or invalid image content");
        }
    }

    private enum ImageFormat {
        PNG("image/png"),
        JPEG("image/jpeg"),
        WEBP("image/webp");

        final String canonicalMime;

        ImageFormat(String canonicalMime) {
            this.canonicalMime = canonicalMime;
        }
    }
}
