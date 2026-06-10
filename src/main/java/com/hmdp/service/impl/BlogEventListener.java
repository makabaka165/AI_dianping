package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.BlogProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_HOT_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Slf4j
@Component
public class BlogEventListener {

    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private BlogMapper blogMapper;

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
        try {
            String key = BLOG_LIKED_KEY + event.getBlogId();
            if (event.isLiked()) {
                stringRedisTemplate.opsForZSet().add(
                        key,
                        event.getUserId().toString(),
                        event.getEventTimeMillis()
                );
            } else {
                stringRedisTemplate.opsForZSet().remove(key, event.getUserId().toString());
            }
            updateHotBlogScore(event.getBlogId());
        } catch (RuntimeException e) {
            log.warn("handle blog like event failed, blogId={}, userId={}",
                    event.getBlogId(), event.getUserId(), e);
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
