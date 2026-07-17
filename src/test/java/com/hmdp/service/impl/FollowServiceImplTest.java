package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.common.ErrorCode;
import com.hmdp.config.BlogProperties;
import com.hmdp.dto.PageResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock followCacheLock;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private IUserService userService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private BlogMapper blogMapper;

    private TestableFollowServiceImpl followService;

    @BeforeEach
    void setUp() {
        followService = new TestableFollowServiceImpl();
        BlogProperties blogProperties = new BlogProperties();
        blogProperties.setFeedInboxMaxSize(100);
        ReflectionTestUtils.setField(followService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(followService, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(followService, "userService", userService);
        ReflectionTestUtils.setField(followService, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(followService, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(followService, "blogProperties", blogProperties);

        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(followCacheLock);
        lenient().when(followCacheLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void followShouldSaveRelationAndSyncRedis() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(userService.getById(2L)).thenReturn(user(2L, 1));

        Result result = followService.follow(2L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(followService.existsFollowInDb(1L, 2L)).isTrue();
        verify(setOperations).add(FOLLOW_KEY + 1L, "2");
        verify(stringRedisTemplate).expire(FOLLOW_KEY + 1L, FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void followShouldTreatDuplicateRelationAsSuccessAndRepairRedis() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(userService.getById(2L)).thenReturn(user(2L, 1));
        followService.insertIgnoreResult = 0;
        when(stringRedisTemplate.hasKey(FOLLOW_LOADED_KEY + 1L)).thenReturn(true);

        Result result = followService.follow(2L);

        assertThat(result.getSuccess()).isTrue();
        verify(setOperations).add(FOLLOW_KEY + 1L, "2");
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void followShouldRejectInvalidSelfFollow() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);

        Result result = followService.follow(1L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(userService, never()).getById(any(Serializable.class));
        verify(setOperations, never()).add(anyString(), anyString());
    }

    @Test
    void followShouldRejectMissingTargetUser() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(userService.getById(2L)).thenReturn(null);

        Result result = followService.follow(2L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verify(setOperations, never()).add(anyString(), anyString());
    }

    @Test
    void unfollowShouldBeIdempotentAndCleanFeed() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(stringRedisTemplate.hasKey(FOLLOW_LOADED_KEY + 1L)).thenReturn(true);
        when(blogMapper.selectList(any())).thenReturn(List.of(
                blog(10L, 2L),
                blog(11L, 2L)
        ));

        Result result = followService.unfollow(2L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(followService.removeCalled).isTrue();
        verify(setOperations).remove(FOLLOW_KEY + 1L, "2");
        verify(stringRedisTemplate).expire(FOLLOW_KEY + 1L, FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
        verify(zSetOperations).remove(eq(FEED_KEY + 1L), eq("10"), eq("11"));
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void followShouldRebuildCompleteSetWhenCacheIsCold() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(userService.getById(3L)).thenReturn(user(3L, 1));
        when(stringRedisTemplate.hasKey(FOLLOW_LOADED_KEY + 1L)).thenReturn(false);
        followService.db.put(1L, new LinkedHashSet<>(Collections.singletonList(2L)));

        Result result = followService.follow(3L);

        assertThat(result.getSuccess()).isTrue();
        verify(stringRedisTemplate).delete(FOLLOW_KEY + 1L);
        verify(setOperations).add(FOLLOW_KEY + 1L, "2", "3");
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void isFollowShouldLoadColdCacheThenReadRedis() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(stringRedisTemplate.hasKey(FOLLOW_LOADED_KEY + 1L)).thenReturn(false);
        when(setOperations.isMember(FOLLOW_KEY + 1L, "2")).thenReturn(true);
        followService.db.put(1L, new LinkedHashSet<>(Collections.singletonList(2L)));

        Result result = followService.isFollow(2L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(true);
        verify(stringRedisTemplate).delete(FOLLOW_KEY + 1L);
        verify(setOperations).add(FOLLOW_KEY + 1L, "2");
        verify(valueOperations).set(FOLLOW_LOADED_KEY + 1L, "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void isFollowShouldFallbackToDbWhenRedisFails() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(stringRedisTemplate.hasKey(FOLLOW_LOADED_KEY + 1L)).thenThrow(new RuntimeException("redis down"));
        followService.db.put(1L, new LinkedHashSet<>(Collections.singletonList(2L)));

        Result result = followService.isFollow(2L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void followCommonsShouldLoadCacheAndIgnoreInvalidRedisMembers() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOperations.intersect(FOLLOW_KEY + 1L, FOLLOW_KEY + 2L))
                .thenReturn(new LinkedHashSet<>(Arrays.asList("4", "bad")));
        when(userService.listByIds(Collections.singletonList(4L))).thenReturn(List.of(user(4L, 1)));
        followService.db.put(1L, new LinkedHashSet<>(Arrays.asList(3L, 4L)));
        followService.db.put(2L, new LinkedHashSet<>(Arrays.asList(4L, 5L)));

        Result result = followService.followCommons(2L, 1, 20);

        assertThat(result.getSuccess()).isTrue();
        PageResult<UserDTO> page = (PageResult<UserDTO>) result.getData();
        assertThat(page.getList()).extracting(UserDTO::getId).containsExactly(4L);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getHasMore()).isFalse();
        verify(setOperations).intersect(FOLLOW_KEY + 1L, FOLLOW_KEY + 2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void followCommonsShouldReturnPagedRedisIntersection() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(true);
        when(setOperations.intersect(FOLLOW_KEY + 1L, FOLLOW_KEY + 2L))
                .thenReturn(new LinkedHashSet<>(Arrays.asList("5", "3", "4")));
        when(userService.listByIds(Arrays.asList(3L, 4L))).thenReturn(List.of(user(3L, 1), user(4L, 1)));

        Result result = followService.followCommons(2L, 1, 2);

        assertThat(result.getSuccess()).isTrue();
        PageResult<UserDTO> page = (PageResult<UserDTO>) result.getData();
        assertThat(page.getList()).extracting(UserDTO::getId).containsExactly(3L, 4L);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getHasMore()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void followCommonsShouldFallbackToDbWhenRedisUnavailable() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        when(userService.listByIds(Collections.singletonList(4L))).thenReturn(List.of(user(4L, 1)));
        followService.db.put(1L, new LinkedHashSet<>(Arrays.asList(3L, 4L)));
        followService.db.put(2L, new LinkedHashSet<>(Arrays.asList(4L, 5L)));

        Result result = followService.followCommons(2L, 1, 20);

        assertThat(result.getSuccess()).isTrue();
        PageResult<UserDTO> page = (PageResult<UserDTO>) result.getData();
        assertThat(page.getList()).extracting(UserDTO::getId).containsExactly(4L);
    }

    @Test
    void followCommonsShouldRejectInvalidPageParams() {
        when(currentUserService.requireCurrentUserId()).thenReturn(1L);

        Result currentResult = followService.followCommons(2L, 0, 20);
        Result sizeResult = followService.followCommons(2L, 1, 101);

        assertThat(currentResult.getSuccess()).isFalse();
        assertThat(currentResult.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(sizeResult.getSuccess()).isFalse();
        assertThat(sizeResult.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    private User user(Long id, Integer status) {
        User user = new User();
        user.setId(id);
        user.setNickName("user" + id);
        user.setIcon("/icons/" + id + ".png");
        user.setStatus(status);
        return user;
    }

    private Blog blog(Long id, Long userId) {
        Blog blog = new Blog();
        blog.setId(id);
        blog.setUserId(userId);
        return blog;
    }

    private static class TestableFollowServiceImpl extends FollowServiceImpl {
        private final Map<Long, Set<Long>> db = new HashMap<>();
        private int insertIgnoreResult = 1;
        private boolean removeCalled;

        @Override
        protected int insertFollowIgnore(Long userId, Long followUserId) {
            if (insertIgnoreResult > 0) {
                db.computeIfAbsent(userId, ignored -> new LinkedHashSet<>())
                        .add(followUserId);
            }
            return insertIgnoreResult;
        }

        @Override
        public boolean remove(Wrapper<Follow> queryWrapper) {
            removeCalled = true;
            return true;
        }

        @Override
        protected boolean existsFollowInDb(Long userId, Long followUserId) {
            return db.getOrDefault(userId, Collections.emptySet()).contains(followUserId);
        }

        @Override
        protected List<Long> queryFolloweeIdsFromDb(Long userId) {
            return List.copyOf(db.getOrDefault(userId, Collections.emptySet()));
        }

    }
}
