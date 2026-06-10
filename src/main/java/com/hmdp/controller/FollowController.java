package com.hmdp.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}")
    @SaCheckPermission("follow:write")
    public Result follow(@PathVariable("id") Long followUserId) {
        return followService.follow(followUserId);
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("follow:write")
    public Result unfollow(@PathVariable("id") Long followUserId) {
        return followService.unfollow(followUserId);
    }

    @GetMapping("/{id}/status")
    @SaCheckLogin
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/{id}/common")
    @SaCheckLogin
    public Result followCommons(@PathVariable("id") Long id,
                                @RequestParam(value = "current", defaultValue = "1") Integer current,
                                @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return followService.followCommons(id, current, size);
    }
}
