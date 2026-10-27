package com.taskpilot.users.auth.oauth2;

import com.taskpilot.users.common.enums.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OAuth2UserInfoMapper Unit Tests")
class OAuth2UserInfoMapperTest {

    private OAuth2UserInfoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OAuth2UserInfoMapper();
    }

    @Test
    @DisplayName("Should correctly map Google user attributes")
    void testMapGoogle() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttributes()).thenReturn(Map.of(
                "sub", "google-sub-12345",
                "email", "user@gmail.com",
                "email_verified", true,
                "name", "Google User",
                "picture", "https://lh3.googleusercontent.com/avatar.jpg"
        ));

        OAuth2UserInfo info = mapper.mapGoogle(principal);

        assertNotNull(info);
        assertEquals(AuthProvider.GOOGLE, info.provider());
        assertEquals("google-sub-12345", info.providerSubject());
        assertEquals("user@gmail.com", info.email());
        assertTrue(info.emailVerified());
        assertEquals("Google User", info.fullName());
        assertEquals("https://lh3.googleusercontent.com/avatar.jpg", info.avatarUrl());
    }

    @Test
    @DisplayName("Should correctly map GitHub user attributes with public verified email")
    void testMapGithubWithPublicEmail() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttributes()).thenReturn(Map.of(
                "id", 987654,
                "login", "octocat",
                "name", "The Octocat",
                "email", "octocat@github.com",
                "avatar_url", "https://avatars.githubusercontent.com/u/987654"
        ));

        OAuth2UserInfo info = mapper.mapGithub(principal, null);

        assertNotNull(info);
        assertEquals(AuthProvider.GITHUB, info.provider());
        assertEquals("987654", info.providerSubject());
        assertEquals("octocat@github.com", info.email());
        assertTrue(info.emailVerified());
        assertEquals("The Octocat", info.fullName());
        assertEquals("https://avatars.githubusercontent.com/u/987654", info.avatarUrl());
    }

    @Test
    @DisplayName("Should fallback to login if name is null for GitHub user")
    void testMapGithubFallbackName() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttributes()).thenReturn(Map.of(
                "id", 1234,
                "login", "devuser",
                "email", "dev@example.com"
        ));

        OAuth2UserInfo info = mapper.mapGithub(principal, null);

        assertNotNull(info);
        assertEquals("devuser", info.fullName());
    }
}
