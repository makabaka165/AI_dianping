package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.BlogProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogLike;
import com.hmdp.entity.Follow;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_HOT_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Slf4j
@Component
public class BlogEventListener {

    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;
    private static final String BLOG_LIKE_CACHE_LOCK_PREFIX = "lock:blog:like-cache:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private BlogLikeMapper blogLikeMapper;

    @Resource(name = "businessRedissonClient")
    private RedissonClient redissonClient;

    @Resource
    private BlogProperties blogProperties;

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogPublished(BlogPublishedEvent event) {
        try {
            int fansCount = followService.query()
                    .eq("follow_user_id", event.getAuthorId())
                    .count();
            if (fansCount < blogProperties.getLargeAuthorFansThreshold()) {
                pushToFollowers(event);
            }
            updateHotBlogScore(event.getBlogId());
        } catch (RuntimeException e) {
            log.warn("handle blog published event failed, blogId={}, authorId={}",
                    event.getBlogId(), event.getAuthorId(), e);
        }
    }

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogLikeChanged(BlogLikeChangedEvent event) {
        RLock lock = null;
        boolean locked = false;
        try {
            lock = redissonClient.getLock(BLOG_LIKE_CACHE_LOCK_PREFIX + event.getBlogId());
            lock.lock();
            locked = true;
            BlogLike currentLike = blogLikeMapper.selectOne(new QueryWrapper<BlogLike>()
                    .select("id", "create_time")
                    .eq("blog_id", event.getBlogId())
                    .eq("user_id", event.getUserId())
                    .last("LIMIT 1"));
            String likedKey = BLOG_LIKED_KEY + event.getBlogId();
            if (currentLike != null) {
                stringRedisTemplate.opsForZSet().add(
                        likedKey,
                        event.getUserId().toString(),
                        likeScore(currentLike, event.getEventTimeMillis())
                );
            } else {
                stringRedisTemplate.opsForZSet().remove(likedKey, event.getUserId().toString());
            }
            updateHotBlogScore(event.getBlogId());
        } catch (RuntimeException e) {
            log.warn("handle blog like event failed, blogId={}, userId={}",
                    event.getBlogId(), event.getUserId(), e);
        } finally {
            unlockQuietly(lock, locked);
        }
    }

    private double likeScore(BlogLike currentLike, long fallback) {
        if (currentLike.getCreateTime() == null) {
            return fallback;
        }
        return currentLike.getCreateTime()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private void unlockQuietly(RLock lock, boolean locked) {
        if (!locked || lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("release blog like cache lock failed", e);
        }
    }

    private void pushToFollowers(BlogPublishedEvent event) {
        List<Follow> follows = followService.query()
                .select("user_id")
                .eq("follow_user_id", event.getAuthorId())
                .list();
        for (Follow follow : follows) {
            String key = FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(
                    key,
                    event.getBlogId().toString(),
                    event.getPublishTimeMillis()
            );
            trimFeedInbox(key);
        }
    }

    private void updateHotBlogScore(Long blogId) {
        Blog blog = blogMapper.selectOne(new QueryWrapper<Blog>()
                .select("id", "liked")
                .eq("id", blogId)
                .eq("status", BLOG_STATUS_PUBLISHED)
                .eq("deleted", BLOG_NOT_DELETED)
                .last("LIMIT 1"));
        if (blog == null) {
            stringRedisTemplate.opsForZSet().remove(BLOG_HOT_KEY, blogId.toString());
            return;
        }
        int liked = blog.getLiked() == null ? 0 : blog.getLiked();
        stringRedisTemplate.opsForZSet().add(BLOG_HOT_KEY, blogId.toString(), liked);
        stringRedisTemplate.opsForZSet().removeRange(BLOG_HOT_KEY, 0, -blogProperties.getHotCacheSize() - 1L);
    }

    private void trimFeedInbox(String key) {
        Long size = stringRedisTemplate.opsForZSet().size(key);
        if (size != null && size > blogProperties.getFeedInboxMaxSize()) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, size - blogProperties.getFeedInboxMaxSize() - 1);
        }
    }
}
