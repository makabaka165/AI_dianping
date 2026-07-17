package com.hmdp.service.impl;

import com.hmdp.config.BlogProperties;
import com.hmdp.entity.Blog;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogEventListenerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private IFollowService followService;

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private BlogLikeMapper blogLikeMapper;

    @Mock
    private BlogProperties blogProperties;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private BlogEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BlogEventListener();
        ReflectionTestUtils.setField(listener, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(listener, "followService", followService);
        ReflectionTestUtils.setField(listener, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(listener, "blogLikeMapper", blogLikeMapper);
        ReflectionTestUtils.setField(listener, "blogProperties", blogProperties);
        ReflectionTestUtils.setField(listener, "redissonClient", redissonClient);
        when(redissonClient.getLock("lock:blog:like-cache:11")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(blogProperties.getHotCacheSize()).thenReturn(100);
    }

    @Test
    void staleLikeEventShouldFollowCurrentDatabaseState() {
        Blog blog = new Blog();
        blog.setId(11L);
        blog.setLiked(0);
        when(blogLikeMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        when(blogMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(blog);

        listener.onBlogLikeChanged(new BlogLikeChangedEvent(11L, 7L, true, 100L));

        verify(zSetOperations).remove(BLOG_LIKED_KEY + 11L, "7");
        verify(zSetOperations, never()).add(BLOG_LIKED_KEY + 11L, "7", 100D);
        verify(lock).unlock();
    }
}
