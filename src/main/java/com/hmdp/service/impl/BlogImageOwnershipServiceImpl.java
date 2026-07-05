package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.common.ErrorCode;
import com.hmdp.entity.Blog;
import com.hmdp.exception.BusinessException;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.BlogImageOwnershipService;
import com.hmdp.service.IPermissionService;
import com.hmdp.utils.BlogImagePathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BlogImageOwnershipServiceImpl implements BlogImageOwnershipService {

    private static final String OWNER_KEY_PREFIX = "hmdp:upload:blog:image:";
    private static final String OWNER_KEY_SUFFIX = ":owner";
    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private IPermissionService permissionService;

    @Value("${hmdp.upload.blog-image.owner-ttl-days:7}")
    private long ownerTtlDays;

    @Override
    public void registerOwner(String normalizedPath, Long userId) {
        if (StrUtil.isBlank(normalizedPath) || userId == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(ownerKey(normalizedPath), userId.toString(), ttlDays(), TimeUnit.DAYS);
    }

    @Override
    public boolean canDelete(String normalizedPath, Long userId) {
        if (StrUtil.isBlank(normalizedPath) || userId == null) {
            return false;
        }
        if (permissionService.hasRole(userId, "admin")) {
            return true;
        }
        String owner = queryOwner(normalizedPath);
        if (StrUtil.isNotBlank(owner)) {
            return owner.equals(userId.toString());
        }
        return isImageBoundToUserBlog(normalizedPath, userId);
    }

    @Override
    public String validateAndNormalizeUserImages(String images, Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        List<String> normalizedPaths;
        try {
            normalizedPaths = BlogImagePathUtils.normalizeImageSegments(images);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "blog image path is invalid");
        }
        if (normalizedPaths.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "blog images are required");
        }
        for (String normalizedPath : normalizedPaths) {
            if (!isPublishAllowedForUser(normalizedPath, userId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "blog image is not owned by current user");
            }
        }
        return StrUtil.join(",", normalizedPaths);
    }

    @Override
    public void refreshOwnerTtlForUserImages(String images, Long userId) {
        if (userId == null) {
            return;
        }
        for (String normalizedPath : normalizeSegmentsQuietly(images)) {
            String owner = queryOwner(normalizedPath);
            if (userId.toString().equals(owner)) {
                stringRedisTemplate.expire(ownerKey(normalizedPath), ttlDays(), TimeUnit.DAYS);
            }
        }
    }

    @Override
    public void clearOwner(String normalizedPath) {
        if (StrUtil.isBlank(normalizedPath)) {
            return;
        }
        stringRedisTemplate.delete(ownerKey(normalizedPath));
    }

    String ownerKey(String normalizedPath) {
        return OWNER_KEY_PREFIX + normalizedPath + OWNER_KEY_SUFFIX;
    }

    private String queryOwner(String normalizedPath) {
        try {
            return stringRedisTemplate.opsForValue().get(ownerKey(normalizedPath));
        } catch (RuntimeException e) {
            log.warn("query blog image owner failed, path={}", normalizedPath, e);
            return null;
        }
    }

    private boolean isImageBoundToUserBlog(String normalizedPath, Long userId) {
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>()
                .select("id", "images")
                .eq("user_id", userId)
                .eq("status", BLOG_STATUS_PUBLISHED)
                .eq("deleted", BLOG_NOT_DELETED));
        for (Blog blog : blogs) {
            if (normalizeSegmentsQuietly(blog.getImages()).contains(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPublishAllowedForUser(String normalizedPath, Long userId) {
        String owner = queryOwner(normalizedPath);
        if (userId.toString().equals(owner)) {
            return true;
        }
        return isImageBoundToUserBlog(normalizedPath, userId);
    }

    private List<String> normalizeSegmentsQuietly(String images) {
        if (StrUtil.isBlank(images)) {
            return Collections.emptyList();
        }
        List<String> normalizedPaths = new ArrayList<>();
        for (String segment : images.split(",")) {
            if (StrUtil.isBlank(segment)) {
                continue;
            }
            try {
                normalizedPaths.add(BlogImagePathUtils.normalizeBlogImageName(segment));
            } catch (IllegalArgumentException e) {
                log.warn("ignore invalid legacy blog image path segment, segment={}", segment);
            }
        }
        return normalizedPaths;
    }

    private long ttlDays() {
        return Math.max(ownerTtlDays, 1L);
    }
}
