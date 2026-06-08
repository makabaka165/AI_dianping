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
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwardedFor) && !"unknown".equalsIgnoreCase(forwardedFor)) {
            return StrUtil.maxLength(forwardedFor.split(",")[0].trim(), 64);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return StrUtil.maxLength(realIp, 64);
        }
        return StrUtil.maxLength(request.getRemoteAddr(), 64);
    }

    public static String getUserAgent(HttpServletRequest request) {
        return request == null ? null : StrUtil.maxLength(request.getHeader("User-Agent"), 512);
    }

    public static String getDeviceFingerprint(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String header = request.getHeader("X-Device-Fingerprint");
        if (StrUtil.isBlank(header)) {
            header = request.getHeader("Device-Fingerprint");
        }
        if (StrUtil.isNotBlank(header)) {
            return StrUtil.maxLength(header.trim(), 128);
        }
        String raw = getClientIp(request) + "|" + StrUtil.nullToEmpty(getUserAgent(request));
        return DigestUtil.sha256Hex(raw).substring(0, 64);
    }
}
