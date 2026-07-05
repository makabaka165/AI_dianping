package com.hmdp.security;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SecurityStartupGuard {

    private final Environment environment;

    public SecurityStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validateProductionSafety() {
        if (!isProductionProfile()) {
            return;
        }
        if (hasProfile("dev") || hasProfile("test")) {
            fail("prod profile must not be combined with dev or test profile");
        }
        if (environment.getProperty("hmdp.sms.mock.enabled", Boolean.class, false)) {
            fail("hmdp.sms.mock.enabled must be false in prod profile");
        }
        if (environment.getProperty("hmdp.security.device-fingerprint.trust-client-header", Boolean.class, false)) {
            fail("hmdp.security.device-fingerprint.trust-client-header must be false in prod profile");
        }
        if (environment.getProperty("sa-token.is-share", Boolean.class, false)) {
            fail("sa-token.is-share must be false in prod profile");
        }
        boolean forwardedHeadersEnabled = environment.getProperty(
                "hmdp.security.forwarded-headers.enabled", Boolean.class, false);
        if (forwardedHeadersEnabled) {
            String trustedProxies = environment.getProperty("hmdp.security.forwarded-headers.trusted-proxies", "");
            if (!hasSafeTrustedProxies(trustedProxies)) {
                fail("hmdp.security.forwarded-headers.trusted-proxies must contain explicit trusted proxy IPs in prod profile");
            }
        }
    }

    private boolean isProductionProfile() {
        return hasProfile("prod") || hasProfile("production");
    }

    private boolean hasProfile(String targetProfile) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> targetProfile.equalsIgnoreCase(profile));
    }

    private boolean hasSafeTrustedProxies(String trustedProxies) {
        if (StrUtil.isBlank(trustedProxies)) {
            return false;
        }
        List<String> proxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        return !proxies.isEmpty() && proxies.stream().allMatch(this::isExplicitProxyAddress);
    }

    private boolean isExplicitProxyAddress(String proxy) {
        String normalized = proxy.toLowerCase(Locale.ROOT);
        return !"*".equals(normalized)
                && !"0.0.0.0".equals(normalized)
                && !"0.0.0.0/0".equals(normalized)
                && !"::".equals(normalized)
                && !"::/0".equals(normalized)
                && !normalized.contains("/");
    }

    private void fail(String message) {
        log.error("unsafe production security configuration: {}", message);
        throw new IllegalStateException(message);
    }
}
