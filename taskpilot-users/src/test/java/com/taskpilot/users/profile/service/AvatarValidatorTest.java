package com.taskpilot.users.profile.service;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarValidatorTest {

    private final AvatarValidator validator = new AvatarValidator("https://cdn.taskpilot.local/avatars");

    @Test
    void acceptsValidPngJpegAndWebpWithinLimit() throws Exception {
        byte[] png = encodeImage("png");
        byte[] jpg = encodeImage("jpg");
        byte[] webp = new byte[]{
                'R', 'I', 'F', 'F', 24, 0, 0, 0,
                'W', 'E', 'B', 'P', 'V', 'P', '8', ' ',
                12, 0, 0, 0, 0, 0, 0, 0
        };

        assertThat(validator.validateUploadedImage(new MockMultipartFile("file", "a.png", "image/png", png)))
                .isEqualTo("image/png");
        assertThat(validator.validateUploadedImage(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpg)))
                .isEqualTo("image/jpeg");
        assertThat(validator.validateUploadedImage(new MockMultipartFile("file", "a.webp", "image/webp", webp)))
                .isEqualTo("image/webp");
    }

    @Test
    void rejectsOversizedSvgHtmlPolyglotAndMimeMismatch() throws Exception {
        byte[] oversized = new byte[AvatarValidator.MAX_AVATAR_BYTES + 1];
        assertThatThrownBy(() -> validator.validateUploadedImage(
                new MockMultipartFile("file", "big.png", "image/png", oversized)))
                .isInstanceOf(BusinessException.class);

        byte[] svg = "<svg onload=\"alert(1)\"></svg>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validator.validateUploadedImage(
                new MockMultipartFile("file", "xss.svg", "image/svg+xml", svg)))
                .isInstanceOf(BusinessException.class);

        byte[] fakePngWithHtml = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validator.validateUploadedImage(
                new MockMultipartFile("file", "xss.png", "image/png", fakePngWithHtml)))
                .isInstanceOf(BusinessException.class);

        byte[] png = encodeImage("png");
        assertThatThrownBy(() -> validator.validateUploadedImage(
                new MockMultipartFile("file", "mismatch.jpg", "image/jpeg", png)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void enforcesOriginAllowlistAndPreservesPermittedProviderAvatars() {
        assertThat(validator.validateAndNormalizeProfileAvatarUrl("https://lh3.googleusercontent.com/a/abc"))
                .isEqualTo("https://lh3.googleusercontent.com/a/abc");
        assertThat(validator.validateAndNormalizeProfileAvatarUrl("https://avatars.githubusercontent.com/u/123"))
                .isEqualTo("https://avatars.githubusercontent.com/u/123");
        assertThat(validator.validateAndNormalizeProfileAvatarUrl("https://cdn.taskpilot.local/avatars/u1.png"))
                .isEqualTo("https://cdn.taskpilot.local/avatars/u1.png");

        assertThatThrownBy(() -> validator.validateAndNormalizeProfileAvatarUrl("https://evil.example/avatar.png"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validateAndNormalizeProfileAvatarUrl("https://cdn.taskpilot.local/avatars/xss.svg"))
                .isInstanceOf(BusinessException.class);
        assertThat(validator.sanitizeProviderAvatarUrl("https://evil.example/avatar.png")).isNull();
    }

    private static byte[] encodeImage(String format) throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
