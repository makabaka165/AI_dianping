package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

public final class RequestContextUtils {

    private RequestContextUtils() {
    }

    public static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return null;
        }
        return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
    }

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        try {
            return cleanValue(request.getRemoteAddr(), 64, "unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    public static String getUserAgent(HttpServletRequest request) {
        return request == null ? null : cleanValue(request.getHeader("User-Agent"), 512, null);
    }

    public static String getDeviceFingerprint(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String raw = getClientIp(request) + "|" + StrUtil.nullToEmpty(getUserAgent(request));
        return DigestUtil.sha256Hex(raw).substring(0, 64);
    }

    private static String cleanValue(String value, int maxLength, String defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        String cleaned = value.replaceAll("[\\r\\n\\t\\x00]", "").trim();
        if (StrUtil.isBlank(cleaned) || "unknown".equalsIgnoreCase(cleaned)) {
            return defaultValue;
        }
        return StrUtil.maxLength(cleaned, maxLength);
    }
}
