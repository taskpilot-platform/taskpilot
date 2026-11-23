package com.taskpilot.infrastructure.config.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Trusted ingress proxy configuration for resolving client network identity.
 * Forwarded headers are ignored by default unless explicitly enabled and the immediate
 * socket peer matches a configured trusted CIDR.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.security.trusted-proxy")
public class TrustedProxyProperties {

    private boolean trustForwardedHeaders = false;

    private List<String> trustedCidrs = new ArrayList<>();

    private int maxHeaderLength = 1024;

    private int maxHops = 10;
}
