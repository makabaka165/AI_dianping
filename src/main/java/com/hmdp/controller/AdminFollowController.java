package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.dto.Result;
import com.hmdp.service.FollowConsistencyService;
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
@RequestMapping("/admin/follows")
public class AdminFollowController {

    @Resource
    private FollowConsistencyService followConsistencyService;

    @Resource
    private IOperationLogService operationLogService;

    @PostMapping("/cache/rebuild")
    @SaCheckPermission("follow:repair")
    public Result rebuildFollowCache(@RequestParam(value = "userId", required = false) Long userId) {
        try {
            Map<String, Object> result = userId == null
                    ? followConsistencyService.rebuildAllFollowCaches()
                    : followConsistencyService.rebuildFollowCache(userId);
            record("rebuild_cache", userId, result.toString(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("rebuild_cache", userId, "userId=" + userId, false, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/feed/repair")
    @SaCheckPermission("follow:repair")
    public Result repairUserFeed(@RequestParam("userId") Long userId) {
        try {
            Map<String, Object> result = followConsistencyService.repairUserFeed(userId);
            record("repair_feed", userId, result.toString(), true, null);
            return Result.ok(result);
        } catch (RuntimeException e) {
            record("repair_feed", userId, "userId=" + userId, false, e.getMessage());
            throw e;
        }
    }

    private void record(String operation, Long targetId, String detail, boolean success, String failReason) {
        if (operationLogService == null) {
            return;
        }
        try {
            operationLogService.record("follow", operation, "follow",
                    targetId == null ? null : String.valueOf(targetId), detail, success, failReason);
        } catch (RuntimeException e) {
            log.warn("record admin follow operation failed, operation={}, targetId={}", operation, targetId, e);
        }
    }
}
