package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final long MAX_BLOG_IMAGE_SIZE = 5L * 1024L * 1024L;
    private static final Set<String> ALLOWED_IMAGE_SUFFIXES = Set.of("jpg", "jpeg", "png", "webp");

    @PostMapping("blog")
    @SaCheckLogin
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        validateBlogImage(image);
        String relativeName = createNewFileName(image.getOriginalFilename());
        File target = resolveBlogImageFile(relativeName);
        FileUtil.mkdir(target.getParentFile());
        try {
            image.transferTo(target);
            String publicName = "/" + relativeName;
            log.debug("blog image uploaded, file={}", publicName);
            return Result.ok(publicName);
        } catch (IOException e) {
            throw new RuntimeException("blog image upload failed", e);
        }
    }

    @DeleteMapping("blog")
    @SaCheckLogin
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = resolveBlogImageFile(filename);
        if (!file.isFile()) {
            return Result.ok();
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private void validateBlogImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("image is required");
        }
        if (image.getSize() > MAX_BLOG_IMAGE_SIZE) {
            throw new IllegalArgumentException("image size must be less than or equal to 5MB");
        }
        String suffix = fileSuffix(image.getOriginalFilename());
        if (!ALLOWED_IMAGE_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("image type only supports jpg, jpeg, png and webp");
        }
    }

    private String createNewFileName(String originalFilename) {
        String suffix = fileSuffix(originalFilename);
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private String fileSuffix(String originalFilename) {
        if (StrUtil.isBlank(originalFilename) || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("image filename is invalid");
        }
        return StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
    }

    private File resolveBlogImageFile(String filename) {
        String relativeName = normalizeBlogImageName(filename);
        try {
            File baseDir = new File(SystemConstants.IMAGE_UPLOAD_DIR).getCanonicalFile();
            File target = new File(baseDir, relativeName).getCanonicalFile();
            if (!target.toPath().startsWith(baseDir.toPath())) {
                throw new IllegalArgumentException("image filename is invalid");
            }
            return target;
        } catch (IOException e) {
            throw new RuntimeException("resolve blog image path failed", e);
        }
    }

    private String normalizeBlogImageName(String filename) {
        if (StrUtil.isBlank(filename)) {
            throw new IllegalArgumentException("image filename is required");
        }
        String normalized = filename.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("imgs/")) {
            normalized = normalized.substring("imgs/".length());
        }
        if (!normalized.startsWith("blogs/") || normalized.contains("../")) {
            throw new IllegalArgumentException("image filename is invalid");
        }
        return normalized;
    }
}
