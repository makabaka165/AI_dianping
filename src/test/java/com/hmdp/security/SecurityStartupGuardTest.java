package com.hmdp.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityStartupGuardTest {

    @Test
    void prodShouldRejectMockSmsEnabled() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("hmdp.sms.mock.enabled", "true");

        assertThatThrownBy(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmdp.sms.mock.enabled");
    }

    @Test
    void prodShouldRejectTrustedDeviceFingerprintHeader() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("hmdp.security.device-fingerprint.trust-client-header", "true");

        assertThatThrownBy(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("device-fingerprint");
    }

    @Test
    void prodShouldRejectSharedSaToken() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("sa-token.is-share", "true");

        assertThatThrownBy(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sa-token.is-share");
    }

    @Test
    void prodShouldRejectDevOrTestMixedProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "test");

        assertThatThrownBy(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be combined");
    }

    @Test
    void prodShouldRejectForwardedHeadersWithEmptyTrustedProxies() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("hmdp.security.forwarded-headers.enabled", "true")
                .withProperty("hmdp.security.forwarded-headers.trusted-proxies", " , ");

        assertThatThrownBy(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies");
    }

    @Test
    void prodShouldRejectForwardedHeadersWithWildcardTrustedProxies() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("hmdp.security.forwarded-headers.enabled", "true")
                .withProperty("hmdp.security.forwarded-headers.trusted-proxies", "10.0.0.10,0.0.0.0/0");

        assertThatThrownBy(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies");
    }

    @Test
    void prodShouldAllowExplicitTrustedProxyIps() {
        MockEnvironment environment = prodEnvironment()
                .withProperty("hmdp.security.forwarded-headers.enabled", "true")
                .withProperty("hmdp.security.forwarded-headers.trusted-proxies", "10.0.0.10,172.16.0.20");

        assertThatCode(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .doesNotThrowAnyException();
    }

    @Test
    void nonProdShouldNotApplyProductionGuard() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("hmdp.sms.mock.enabled", "true")
                .withProperty("hmdp.security.forwarded-headers.enabled", "true")
                .withProperty("hmdp.security.forwarded-headers.trusted-proxies", "*");
        environment.setActiveProfiles("test");

        assertThatCode(() -> new SecurityStartupGuard(environment).validateProductionSafety())
                .doesNotThrowAnyException();
    }

    private MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }
}
