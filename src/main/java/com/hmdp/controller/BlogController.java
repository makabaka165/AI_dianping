package com.hmdp.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.ShopSummaryService;
import com.hmdp.utils.LocalCacheManager;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    
    @Resource
    private ShopSummaryService shopSummaryService;

    @PostMapping
    @SaCheckPermission("blog:create")
    public Result saveBlog(@RequestBody Blog blog) {
        Result result = blogService.saveBlog(blog);
        
        // 如果保存成功，更新店铺评价数量缓存
        if (result.getSuccess() && blog.getShopId() != null) {
            // 由于我们不知道新的评价数量，所以从缓存中移除该店铺的评价数量
            // 下次获取时会重新查询数据库
            String cacheKey = "shop_review_count_" + blog.getShopId();
            shopSummaryService.getLocalCacheManager().remove(cacheKey, LocalCacheManager.CacheType.SHOP_STATS);
            // 同样移除店铺存在性缓存，确保下次重新检查
            String existsCacheKey = "shop_exists_" + blog.getShopId();
            shopSummaryService.getLocalCacheManager().remove(existsCacheKey, LocalCacheManager.CacheType.SHOP_INFO);
        }
        
        return result;
    }

    @PutMapping("/like/{id}")
    @SaCheckPermission("blog:like")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    @SaCheckLogin
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    @SaCheckLogin
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }
}
