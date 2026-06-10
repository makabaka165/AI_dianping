package com.hmdp.service;

import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_CACHE_TTL;
import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_LOADED_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowConsistencyServiceTest {

    @Mock
    private FollowMapper followMapper;

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private FollowConsistencyService followConsistencyService;

    @BeforeEach
    void setUp() {
        followConsistencyService = new FollowConsistencyService();
        ReflectionTestUtils.setField(followConsistencyService, "followMapper", followMapper);
        ReflectionTestUtils.setField(followConsistencyService, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(followConsistencyService, "stringRedisTemplate", stringRedisTemplate);

        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void rebuildFollowCacheShouldReplaceRedisSetAndLoadedMarker() {
        when(followMapper.selectList(any())).thenReturn(List.of(
                follow(1L, 3L),
                follow(1L, 2L)
        ));

        Map<String, Object> result = followConsistencyService.rebuildFollowCache(1L);

        assertThat(result).containsEntry("userId", 1L)
                .containsEntry("followCount", 2)
                .containsEntry("rebuiltUsers", 1);
        verify(stringRedisTemplate).delete(FOLLOW_KEY + 1L);
        verify(setOperations).add(FOLLOW_KEY + 1L, "2", "3");
        verify(stringRedisTemplate).expire(FOLLOW_KEY + 1L, FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void rebuildAllFollowCachesShouldGroupRelationsByUser() {
        when(followMapper.selectList(any())).thenReturn(List.of(
                follow(1L, 3L),
                follow(1L, 2L),
                follow(2L, 4L)
        ));

        Map<String, Object> result = followConsistencyService.rebuildAllFollowCaches();

        assertThat(result).containsEntry("rebuiltUsers", 2)
                .containsEntry("rebuiltRelations", 3);
        verify(setOperations).add(FOLLOW_KEY + 1L, "2", "3");
        verify(setOperations).add(FOLLOW_KEY + 2L, "4");
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 2L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void repairUserFeedShouldRemoveBlogsNotBelongingToCurrentFollowees() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                tuple("10"),
                tuple("11"),
                tuple("bad")
        ));
        when(zSetOperations.rangeWithScores(FEED_KEY + 1L, 0, -1)).thenReturn(tuples);
        when(followMapper.selectList(any())).thenReturn(List.of(follow(1L, 2L)));
        when(blogMapper.selectList(any())).thenReturn(List.of(
                blog(10L, 2L),
                blog(11L, 3L)
        ));

        Map<String, Object> result = followConsistencyService.repairUserFeed(1L);

        assertThat(result).containsEntry("userId", 1L)
                .containsEntry("scannedFeedItems", 3)
                .containsEntry("removedFeedItems", 2);
        verify(zSetOperations).remove(FEED_KEY + 1L, "bad", "11");
    }

    @Test
    void repairUserFeedShouldDeleteFeedWhenUserHasNoFollowees() {
        Set<ZSetOperations.TypedTuple<String>> tuples = Set.of(tuple("10"));
        when(zSetOperations.rangeWithScores(FEED_KEY + 1L, 0, -1)).thenReturn(tuples);
        when(followMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = followConsistencyService.repairUserFeed(1L);

        assertThat(result).containsEntry("scannedFeedItems", 1)
                .containsEntry("removedFeedItems", 1);
        verify(stringRedisTemplate).delete(FEED_KEY + 1L);
    }

    @Test
    void rebuildFollowCacheShouldRejectInvalidUserId() {
        assertThatThrownBy(() -> followConsistencyService.rebuildFollowCache(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Follow follow(Long userId, Long followUserId) {
        return new Follow().setUserId(userId).setFollowUserId(followUserId);
    }

    private Blog blog(Long id, Long userId) {
        return new Blog().setId(id).setUserId(userId);
    }

    @SuppressWarnings("unchecked")
    private ZSetOperations.TypedTuple<String> tuple(String value) {
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        lenient().when(tuple.getValue()).thenReturn(value);
        return tuple;
    }
}
