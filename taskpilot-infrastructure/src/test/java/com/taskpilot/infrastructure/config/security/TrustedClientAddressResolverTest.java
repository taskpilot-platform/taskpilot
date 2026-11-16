package com.taskpilot.infrastructure.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class TrustedClientAddressResolverTest {

    @Test
    void ignoresSpoofedForwardingHeadersWhenTrustDisabled() {
        TrustedProxyProperties props = new TrustedProxyProperties();
        props.setTrustForwardedHeaders(false);
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver(props);

        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("198.51.100.14");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99");

        assertThat(resolver.resolve(req)).isEqualTo("198.51.100.14");
    }

    @Test
    void ignoresXForwardedForFromUntrustedPeerEvenWhenEnabled() {
        TrustedProxyProperties props = new TrustedProxyProperties();
        props.setTrustForwardedHeaders(true);
        props.setTrustedCidrs(List.of("10.0.0.0/8"));
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver(props);

        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("198.51.100.14");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99");

        assertThat(resolver.resolve(req)).isEqualTo("198.51.100.14");
    }

    @Test
    void stripsTrustedProxyHopsRightToLeftAndReturnsRealClientIp() {
        TrustedProxyProperties props = new TrustedProxyProperties();
        props.setTrustForwardedHeaders(true);
        props.setTrustedCidrs(List.of("10.0.0.0/8", "172.16.0.0/12"));
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver(props);

        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.2.3.4");
        // Client spoofed 1.2.3.4 at left; edge ingress recorded real client 203.0.113.77 followed by trusted hop
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 203.0.113.77, 172.16.5.9");

        assertThat(resolver.resolve(req)).isEqualTo("203.0.113.77");
    }

    @Test
    void supportsIpv6CidrAndRejectsMalformedOrOversizedHeader() {
        TrustedProxyProperties props = new TrustedProxyProperties();
        props.setTrustForwardedHeaders(true);
        props.setTrustedCidrs(List.of("fc00::/7"));
        props.setMaxHeaderLength(64);
        props.setMaxHops(3);
        TrustedClientAddressResolver resolver = new TrustedClientAddressResolver(props);

        HttpServletRequest ipv6Req = Mockito.mock(HttpServletRequest.class);
        when(ipv6Req.getRemoteAddr()).thenReturn("fd00:0:0:0:0:0:0:1");
        when(ipv6Req.getHeader("X-Forwarded-For")).thenReturn("2001:db8::5, fd00::2");
        assertThat(resolver.resolve(ipv6Req)).isEqualTo("2001:db8:0:0:0:0:0:5");

        HttpServletRequest malformedReq = Mockito.mock(HttpServletRequest.class);
        when(malformedReq.getRemoteAddr()).thenReturn("fd00::1");
        when(malformedReq.getHeader("X-Forwarded-For")).thenReturn("not-an-ip.example, fd00::2");
        assertThat(resolver.resolve(malformedReq)).isEqualTo("fd00:0:0:0:0:0:0:1");

        HttpServletRequest tooManyHopsReq = Mockito.mock(HttpServletRequest.class);
        when(tooManyHopsReq.getRemoteAddr()).thenReturn("fd00::1");
        when(tooManyHopsReq.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, 2.2.2.2, 3.3.3.3, 4.4.4.4");
        assertThat(resolver.resolve(tooManyHopsReq)).isEqualTo("fd00:0:0:0:0:0:0:1");
    }
}
