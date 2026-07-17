package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.service.BlogImageOwnershipService;
import com.hmdp.service.CurrentUserService;
import com.hmdp.utils.BlogImagePathUtils;
import com.hmdp.utils.ImageTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final long MAX_BLOG_IMAGE_SIZE = 5L * 1024L * 1024L;
    private static final Set<String> ALLOWED_IMAGE_SUFFIXES = Set.of("jpg", "jpeg", "png", "webp");

    @Value("${hmdp.upload.image-dir:D:/lesson/nginx-1.18.0/html/hmdp/imgs/}")
    private String imageUploadDir;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private BlogImageOwnershipService blogImageOwnershipService;

    @PostMapping("blog")
    @SaCheckLogin
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        validateBlogImage(image);
        String relativeName = createNewFileName(image.getOriginalFilename());
        File target = resolveBlogImageFile(relativeName);
        FileUtil.mkdir(target.getParentFile());
        try {
            image.transferTo(target);
            blogImageOwnershipService.registerOwner(relativeName, currentUserService.requireCurrentUserId());
            String publicName = "/" + relativeName;
            log.debug("blog image uploaded, file={}", publicName);
            return Result.ok(publicName);
        } catch (IOException e) {
            deleteFailedUpload(target);
            throw new RuntimeException("blog image upload failed", e);
        } catch (RuntimeException e) {
            deleteFailedUpload(target);
            throw e;
        }
    }

    @DeleteMapping("blog")
    @SaCheckLogin
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        String relativeName = normalizeBlogImageName(filename);
        Long currentUserId = currentUserService.requireCurrentUserId();
        File file = resolveBlogImageFile(relativeName);
        if (!file.isFile()) {
            return Result.ok();
        }
        if (!blogImageOwnershipService.canDelete(relativeName, currentUserId)) {
            return Result.fail(ErrorCode.FORBIDDEN, "no permission to delete this image");
        }
        FileUtil.del(file);
        if (file.exists()) {
            throw new IllegalStateException("blog image delete failed");
        }
        blogImageOwnershipService.clearOwner(relativeName);
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
        ImageTypeValidator.validateMagicBytes(image, suffix);
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
            File baseDir = new File(imageUploadDir).getCanonicalFile();
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
        return BlogImagePathUtils.normalizeBlogImageName(filename);
    }

    private void deleteFailedUpload(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        try {
            if (!FileUtil.del(target)) {
                log.warn("failed to remove incomplete blog image, file={}", target.getAbsolutePath());
            }
        } catch (RuntimeException cleanupError) {
            log.warn("failed to remove incomplete blog image, file={}", target.getAbsolutePath(), cleanupError);
        }
    }
}
