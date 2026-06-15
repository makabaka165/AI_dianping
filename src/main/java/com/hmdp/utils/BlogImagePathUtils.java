package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class BlogImagePathUtils {

    private BlogImagePathUtils() {
    }

    public static String normalizeBlogImageName(String filename) {
        if (StrUtil.isBlank(filename)) {
            throw new IllegalArgumentException("image filename is required");
        }
        String normalized = filename.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("imgs/")) {
            normalized = normalized.substring("imgs/".length());
        }
        if (!normalized.startsWith("blogs/")) {
            throw new IllegalArgumentException("image filename is invalid");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (StrUtil.isBlank(segment) || ".".equals(segment) || "..".equals(segment) || segment.startsWith("..")) {
                throw new IllegalArgumentException("image filename is invalid");
            }
        }
        return normalized;
    }

    public static List<String> normalizeImageSegments(String images) {
        if (StrUtil.isBlank(images)) {
            return Collections.emptyList();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(BlogImagePathUtils::normalizeBlogImageName)
                .collect(Collectors.toList());
    }
}
