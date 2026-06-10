package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.dto.Result;
import com.hmdp.service.BlogConsistencyService;
import com.hmdp.service.IOperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin/blogs")
public class AdminBlogController {

    @Resource
    private BlogConsistencyService blogConsistencyService;

    @Resource
    private IOperationLogService operationLogService;

    @PostMapping("/likes/repair")
    @SaCheckPermission("blog:repair")
    public Result repairBlogLikes(@RequestParam(value = "blogId", required = false) Long blogId) {
        try {
            Map<String, Object> result = blogId == null
                    ? blogConsistencyService.repairAllBlogLikes()
                    : blogConsistencyService.repairBlogLikes(blogId);
            record("repair_likes", blogId, result.toString(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("repair_likes", blogId, "blogId=" + blogId, false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/hot/rebuild")
    @SaCheckPermission("blog:repair")
    public Result rebuildHotBlogs() {
        try {
            Map<String, Object> result = blogConsistencyService.rebuildHotBlogs();
            record("rebuild_hot", null, result.toString(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("rebuild_hot", null, "blog hot rebuild", false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/likes/migrate-legacy")
    @SaCheckPermission("blog:repair")
    public Result migrateLegacyRedisLikes(@RequestParam(value = "blogId", required = false) Long blogId) {
        try {
            Map<String, Object> result = blogConsistencyService.migrateLegacyRedisLikes(blogId);
            record("migrate_legacy_likes", blogId, result.toString(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("migrate_legacy_likes", blogId, "blogId=" + blogId, false, e.getMessage());
            throw e;
        }
    }

    private void record(String operation, Long targetId, String detail, boolean success, String failReason) {
        if (operationLogService == null) {
            return;
        }
        try {
            operationLogService.record("blog", operation, "blog",
                    targetId == null ? null : String.valueOf(targetId), detail, success, failReason);
        } catch (RuntimeException e) {
            log.warn("record admin blog operation failed, operation={}, targetId={}", operation, targetId, e);
        }
    }
}
