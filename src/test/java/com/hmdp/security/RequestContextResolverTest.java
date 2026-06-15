package com.hmdp.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextResolverTest {

    @Test
    void defaultShouldIgnoreForwardedForAndUseRemoteAddr() {
        RequestContextResolver resolver = new RequestContextResolver(false, "127.0.0.1,::1", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(resolver.getClientIp(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void trustedProxyShouldUseFirstForwardedForIpWhenEnabled() {
        RequestContextResolver resolver = new RequestContextResolver(true, "127.0.0.1,::1", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", " 1.2.3.4, 5.6.7.8 ");

        assertThat(resolver.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    void trustedProxyShouldSkipUnknownForwardedForSegments() {
        RequestContextResolver resolver = new RequestContextResolver(true, "127.0.0.1", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "unknown, 1.2.3.4");

        assertThat(resolver.getClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    void untrustedRemoteShouldIgnoreForwardedForEvenWhenEnabled() {
        RequestContextResolver resolver = new RequestContextResolver(true, "127.0.0.1", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(resolver.getClientIp(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void defaultDeviceFingerprintShouldNotUseClientHeaderDirectly() {
        RequestContextResolver resolver = new RequestContextResolver(false, "127.0.0.1", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("User-Agent", "JUnit");
        request.addHeader("X-Device-Fingerprint", "attacker-device");

        String fingerprint = resolver.getDeviceFingerprint(request);

        assertThat(fingerprint).hasSize(64);
        assertThat(fingerprint).doesNotContain("attacker-device");
    }

    @Test
    void trustedDeviceHeaderShouldStillReturnHashOnly() {
        RequestContextResolver resolver = new RequestContextResolver(false, "127.0.0.1", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("User-Agent", "JUnit");
        request.addHeader("X-Device-Fingerprint", "client-device-value");

        String fingerprint = resolver.getDeviceFingerprint(request);

        assertThat(fingerprint).hasSize(64);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(fingerprint).doesNotContain("client-device-value");
    }
}
