package com.hmdp.security;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hmdp.utils.RequestContextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RequestContextResolver {

    private final boolean forwardedHeadersEnabled;
    private final Set<String> trustedProxies;
    private final boolean trustClientDeviceFingerprintHeader;

    public RequestContextResolver(
            @Value("${hmdp.security.forwarded-headers.enabled:false}") boolean forwardedHeadersEnabled,
            @Value("${hmdp.security.forwarded-headers.trusted-proxies:127.0.0.1,::1}") String trustedProxies,
            @Value("${hmdp.security.device-fingerprint.trust-client-header:false}") boolean trustClientDeviceFingerprintHeader) {
        this.forwardedHeadersEnabled = forwardedHeadersEnabled;
        this.trustedProxies = parseTrustedProxies(trustedProxies);
        this.trustClientDeviceFingerprintHeader = trustClientDeviceFingerprintHeader;
    }

    public String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        try {
            String remoteAddr = cleanIp(request.getRemoteAddr());
            if (!forwardedHeadersEnabled || !trustedProxies.contains(remoteAddr)) {
                return remoteAddr;
            }
            String forwardedFor = firstForwardedIp(request.getHeader("X-Forwarded-For"));
            if (StrUtil.isNotBlank(forwardedFor)) {
                return forwardedFor;
            }
            String realIp = cleanHeader(request.getHeader("X-Real-IP"), 64);
            return isUsableIp(realIp) ? realIp : remoteAddr;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    public String getDeviceFingerprint(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String clientIp = getClientIp(request);
        String userAgent = StrUtil.nullToEmpty(RequestContextUtils.getUserAgent(request));
        String clientHeader = "";
        if (trustClientDeviceFingerprintHeader) {
            clientHeader = cleanHeader(request.getHeader("X-Device-Fingerprint"), 512);
            if (StrUtil.isBlank(clientHeader)) {
                clientHeader = cleanHeader(request.getHeader("Device-Fingerprint"), 512);
            }
        }
        String raw = clientHeader + "|" + clientIp + "|" + userAgent;
        return DigestUtil.sha256Hex(raw).substring(0, 64);
    }

    private static Set<String> parseTrustedProxies(String trustedProxies) {
        if (StrUtil.isBlank(trustedProxies)) {
            return Collections.emptySet();
        }
        return Arrays.stream(trustedProxies.split(","))
                .map(RequestContextResolver::cleanIp)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    private static String firstForwardedIp(String forwardedFor) {
        if (StrUtil.isBlank(forwardedFor)) {
            return null;
        }
        return Arrays.stream(forwardedFor.split(","))
                .map(RequestContextResolver::cleanIp)
                .filter(RequestContextResolver::isUsableIp)
                .findFirst()
                .orElse(null);
    }

    private static String cleanIp(String value) {
        String cleaned = cleanHeader(value, 64);
        if (StrUtil.isBlank(cleaned) || "unknown".equalsIgnoreCase(cleaned)) {
            return "unknown";
        }
        return cleaned;
    }

    private static String cleanHeader(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String trimmed = value.replaceAll("[\\r\\n\\t\\x00]", "").trim();
        return StrUtil.maxLength(trimmed, maxLength);
    }

    private static boolean isUsableIp(String value) {
        return StrUtil.isNotBlank(value) && !"unknown".equalsIgnoreCase(value);
    }
}
