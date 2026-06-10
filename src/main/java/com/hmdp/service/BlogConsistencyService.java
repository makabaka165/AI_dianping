package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.config.BlogProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogLike;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.BLOG_HOT_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

@Slf4j
@Service
public class BlogConsistencyService {

    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private BlogLikeMapper blogLikeMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BlogProperties blogProperties;

    @Transactional
    public Map<String, Object> repairBlogLikes(Long blogId) {
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) {
            throw new IllegalArgumentException("blog not found");
        }
        List<BlogLike> likes = blogLikeMapper.selectList(new QueryWrapper<BlogLike>()
                .eq("blog_id", blogId)
                .orderByAsc("create_time"));
        rebuildBlogLikedZSet(blogId, likes);
        blogMapper.update(null, new UpdateWrapper<Blog>()
                .set("liked", likes.size())
                .eq("id", blogId));
        updateHotBlogScore(blogId, likes.size(), blog);

        Map<String, Object> result = new HashMap<>();
        result.put("blogId", blogId);
        result.put("liked", likes.size());
        result.put("repairedBlogs", 1);
        return result;
    }

    @Transactional
    public Map<String, Object> repairAllBlogLikes() {
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>().select("id", "status", "deleted"));
        int repairedLikes = 0;
        for (Blog blog : blogs) {
            List<BlogLike> likes = blogLikeMapper.selectList(new QueryWrapper<BlogLike>()
                    .eq("blog_id", blog.getId())
                    .orderByAsc("create_time"));
            rebuildBlogLikedZSet(blog.getId(), likes);
            blogMapper.update(null, new UpdateWrapper<Blog>()
                    .set("liked", likes.size())
                    .eq("id", blog.getId()));
            updateHotBlogScore(blog.getId(), likes.size(), blog);
            repairedLikes += likes.size();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("repairedBlogs", blogs.size());
        result.put("repairedLikes", repairedLikes);
        return result;
    }

    public Map<String, Object> rebuildHotBlogs() {
        stringRedisTemplate.delete(BLOG_HOT_KEY);
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>()
                .select("id", "liked")
                .eq("status", BLOG_STATUS_PUBLISHED)
                .eq("deleted", BLOG_NOT_DELETED)
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .last("LIMIT " + blogProperties.getHotCacheSize()));
        for (Blog blog : blogs) {
            stringRedisTemplate.opsForZSet().add(
                    BLOG_HOT_KEY,
                    blog.getId().toString(),
                    blog.getLiked() == null ? 0D : blog.getLiked().doubleValue()
            );
        }

        Map<String, Object> result = new HashMap<>();
        result.put("rebuiltHotBlogs", blogs.size());
        return result;
    }

    @Transactional
    public Map<String, Object> migrateLegacyRedisLikes(Long blogId) {
        Set<Long> blogIds = blogId == null ? findLegacyLikedBlogIds() : Collections.singleton(blogId);
        int importedLikes = 0;
        int duplicateLikes = 0;
        for (Long id : blogIds) {
            LegacyImportResult importResult = importLegacyLikedMembers(id);
            importedLikes += importResult.imported;
            duplicateLikes += importResult.duplicates;
            repairBlogLikes(id);
        }
        rebuildHotBlogs();

        Map<String, Object> result = new HashMap<>();
        result.put("scannedBlogs", blogIds.size());
        result.put("importedLikes", importedLikes);
        result.put("duplicateLikes", duplicateLikes);
        return result;
    }

    private void rebuildBlogLikedZSet(Long blogId, List<BlogLike> likes) {
        String key = BLOG_LIKED_KEY + blogId;
        stringRedisTemplate.delete(key);
        for (BlogLike like : likes) {
            stringRedisTemplate.opsForZSet().add(
                    key,
                    like.getUserId().toString(),
                    toEpochMilli(like.getCreateTime())
            );
        }
    }

    private void updateHotBlogScore(Long blogId, int liked, Blog blog) {
        boolean visible = Integer.valueOf(BLOG_STATUS_PUBLISHED).equals(blog.getStatus())
                && Integer.valueOf(BLOG_NOT_DELETED).equals(blog.getDeleted());
        if (visible) {
            stringRedisTemplate.opsForZSet().add(BLOG_HOT_KEY, blogId.toString(), liked);
            stringRedisTemplate.opsForZSet().removeRange(BLOG_HOT_KEY, 0, -blogProperties.getHotCacheSize() - 1L);
        } else {
            stringRedisTemplate.opsForZSet().remove(BLOG_HOT_KEY, blogId.toString());
        }
    }

    private Set<Long> findLegacyLikedBlogIds() {
        Set<String> keys = stringRedisTemplate.keys(BLOG_LIKED_KEY + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> blogIds = new HashSet<>();
        for (String key : keys) {
            String rawId = key.substring(BLOG_LIKED_KEY.length());
            try {
                blogIds.add(Long.valueOf(rawId));
            } catch (NumberFormatException e) {
                log.warn("ignore invalid legacy blog liked key: {}", key);
            }
        }
        return blogIds;
    }

    private LegacyImportResult importLegacyLikedMembers(Long blogId) {
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .rangeWithScores(BLOG_LIKED_KEY + blogId, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return new LegacyImportResult(0, 0);
        }
        int imported = 0;
        int duplicates = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null) {
                continue;
            }
            try {
                BlogLike like = new BlogLike()
                        .setBlogId(blogId)
                        .setUserId(Long.valueOf(tuple.getValue()))
                        .setCreateTime(fromScore(tuple.getScore()));
                blogLikeMapper.insert(like);
                imported++;
            } catch (DuplicateKeyException e) {
                duplicates++;
            } catch (NumberFormatException e) {
                log.warn("ignore invalid legacy blog liked member, blogId={}, member={}", blogId, tuple.getValue());
            }
        }
        return new LegacyImportResult(imported, duplicates);
    }

    private long toEpochMilli(LocalDateTime time) {
        LocalDateTime value = time == null ? LocalDateTime.now() : time;
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime fromScore(Double score) {
        long epochMillis = score == null ? System.currentTimeMillis() : score.longValue();
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static class LegacyImportResult {
        private final int imported;
        private final int duplicates;

        private LegacyImportResult(int imported, int duplicates) {
            this.imported = imported;
            this.duplicates = duplicates;
        }
    }
}
