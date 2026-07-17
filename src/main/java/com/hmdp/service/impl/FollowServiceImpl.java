package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.config.BlogProperties;
import com.hmdp.dto.PageResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_CACHE_TTL;
import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_LOADED_KEY;

/**
 * Follow relation service.
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private static final int USER_STATUS_DISABLED = 0;
    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;
    private static final int DEFAULT_COMMON_PAGE_SIZE = 20;
    private static final int MAX_COMMON_PAGE_SIZE = 100;
    private static final String FOLLOW_CACHE_LOCK_PREFIX = "lock:follow:cache:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "businessRedissonClient")
    private RedissonClient redissonClient;

    @Resource
    private IUserService userService;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private BlogProperties blogProperties;

    @Override
    @Transactional
    public Result follow(Long followUserId) {
        Long userId = currentUserService.requireCurrentUserId();
        Result validateResult = validateFollowRequest(userId, followUserId);
        if (validateResult != null) {
            return validateResult;
        }

        Result targetResult = validateFollowTarget(followUserId);
        if (targetResult != null) {
            return targetResult;
        }
        saveFollowRelation(userId, followUserId);
        afterCommitOrNow(() -> syncFollowCache(userId, followUserId, true));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result unfollow(Long followUserId) {
        Long userId = currentUserService.requireCurrentUserId();
        Result validateResult = validateFollowRequest(userId, followUserId);
        if (validateResult != null) {
            return validateResult;
        }

        remove(new QueryWrapper<Follow>()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId));
        afterCommitOrNow(() -> {
            syncFollowCache(userId, followUserId, false);
            removeUnfollowedAuthorFromFeed(userId, followUserId);
        });
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = currentUserService.requireCurrentUserId();
        if (isInvalidId(followUserId)) {
            return Result.fail(ErrorCode.PARAM_ERROR, "follow user id is invalid");
        }
        if (Objects.equals(userId, followUserId)) {
            return Result.ok(false);
        }

        if (ensureFollowCacheLoaded(userId)) {
            try {
                Boolean isMember = stringRedisTemplate.opsForSet()
                        .isMember(followKey(userId), followUserId.toString());
                return Result.ok(Boolean.TRUE.equals(isMember));
            } catch (RuntimeException e) {
                log.warn("query follow cache failed, userId={}, followUserId={}", userId, followUserId, e);
            }
        }
        return Result.ok(existsFollowInDb(userId, followUserId));
    }

    @Override
    public Result followCommons(Long id, Integer current, Integer size) {
        Long userId = currentUserService.requireCurrentUserId();
        if (isInvalidId(id)) {
            return Result.fail(ErrorCode.PARAM_ERROR, "user id is invalid");
        }
        int pageNo = current == null ? 1 : current;
        int pageSize = size == null ? DEFAULT_COMMON_PAGE_SIZE : size;
        if (pageNo < 1) {
            return Result.fail(ErrorCode.PARAM_ERROR, "current must be greater than 0");
        }
        if (pageSize < 1 || pageSize > MAX_COMMON_PAGE_SIZE) {
            return Result.fail(ErrorCode.PARAM_ERROR, "size must be between 1 and 100");
        }

        boolean cacheReady = ensureFollowCacheLoaded(userId) && ensureFollowCacheLoaded(id);
        if (cacheReady) {
            try {
                Set<String> intersect = stringRedisTemplate.opsForSet().intersect(followKey(userId), followKey(id));
                return Result.ok(toUserPage(parseFollowIds(intersect), pageNo, pageSize));
            } catch (RuntimeException e) {
                log.warn("query common follows from redis failed, userId={}, targetUserId={}", userId, id, e);
            }
        }
        return Result.ok(toUserPage(queryCommonFolloweeIdsFromDb(userId, id), pageNo, pageSize));
    }

    private void saveFollowRelation(Long userId, Long followUserId) {
        int inserted = insertFollowIgnore(userId, followUserId);
        if (inserted == 0) {
            log.info("ignore duplicate follow relation, userId={}, followUserId={}", userId, followUserId);
        }
    }

    protected int insertFollowIgnore(Long userId, Long followUserId) {
        return baseMapper.insertIgnore(userId, followUserId);
    }

    private Result validateFollowRequest(Long userId, Long followUserId) {
        if (isInvalidId(followUserId)) {
            return Result.fail(ErrorCode.PARAM_ERROR, "follow user id is invalid");
        }
        if (Objects.equals(userId, followUserId)) {
            return Result.fail(ErrorCode.PARAM_ERROR, "cannot follow yourself");
        }
        return null;
    }

    private Result validateFollowTarget(Long followUserId) {
        User targetUser = userService.getById(followUserId);
        if (targetUser == null) {
            return Result.fail(ErrorCode.NOT_FOUND, "follow user not found");
        }
        if (Integer.valueOf(USER_STATUS_DISABLED).equals(targetUser.getStatus())) {
            return Result.fail(ErrorCode.BUSINESS_ERROR, "follow user is disabled");
        }
        return null;
    }

    protected boolean existsFollowInDb(Long userId, Long followUserId) {
        return getOne(new QueryWrapper<Follow>()
                .select("id")
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .last("LIMIT 1"), false) != null;
    }

    protected List<Long> queryFolloweeIdsFromDb(Long userId) {
        return list(new QueryWrapper<Follow>()
                .select("follow_user_id")
                .eq("user_id", userId))
                .stream()
                .map(Follow::getFollowUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<Long> queryCommonFolloweeIdsFromDb(Long userId, Long targetUserId) {
        Set<Long> currentUserFollows = new HashSet<>(queryFolloweeIdsFromDb(userId));
        if (currentUserFollows.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> targetUserFollows = new HashSet<>(queryFolloweeIdsFromDb(targetUserId));
        currentUserFollows.retainAll(targetUserFollows);
        return currentUserFollows.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    protected boolean ensureFollowCacheLoaded(Long userId) {
        RLock lock = null;
        boolean locked = false;
        try {
            Boolean loaded = stringRedisTemplate.hasKey(loadedKey(userId));
            if (Boolean.TRUE.equals(loaded)) {
                return true;
            }
            lock = redissonClient.getLock(FOLLOW_CACHE_LOCK_PREFIX + userId);
            lock.lock();
            locked = true;
            loaded = stringRedisTemplate.hasKey(loadedKey(userId));
            if (Boolean.TRUE.equals(loaded)) {
                return true;
            }
            List<Long> followeeIds = queryFolloweeIdsFromDb(userId);
            rebuildFollowCache(userId, followeeIds);
            return true;
        } catch (RuntimeException e) {
            log.warn("rebuild follow cache failed, userId={}", userId, e);
            return false;
        } finally {
            unlockQuietly(lock, locked);
        }
    }

    private void rebuildFollowCache(Long userId, List<Long> followeeIds) {
        String key = followKey(userId);
        stringRedisTemplate.delete(key);
        if (followeeIds != null && !followeeIds.isEmpty()) {
            String[] members = followeeIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toArray(String[]::new);
            if (members.length > 0) {
                stringRedisTemplate.opsForSet().add(key, members);
                stringRedisTemplate.expire(key, FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
            }
        }
        markFollowCacheLoaded(userId);
    }

    private void syncFollowCache(Long userId, Long followUserId, boolean follow) {
        RLock lock = null;
        boolean locked = false;
        try {
            lock = redissonClient.getLock(FOLLOW_CACHE_LOCK_PREFIX + userId);
            lock.lock();
            locked = true;
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(loadedKey(userId)))) {
                rebuildFollowCache(userId, queryFolloweeIdsFromDb(userId));
                return;
            }
            String key = followKey(userId);
            if (follow) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            } else {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
            stringRedisTemplate.expire(key, FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
            markFollowCacheLoaded(userId);
        } catch (RuntimeException e) {
            log.warn("sync follow cache failed, userId={}, followUserId={}, follow={}",
                    userId, followUserId, follow, e);
        } finally {
            unlockQuietly(lock, locked);
        }
    }

    private void markFollowCacheLoaded(Long userId) {
        stringRedisTemplate.opsForValue()
                .set(loadedKey(userId), "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    private List<Long> parseFollowIds(Set<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(rawIds.size());
        for (String rawId : rawIds) {
            try {
                ids.add(Long.valueOf(rawId));
            } catch (NumberFormatException e) {
                log.warn("ignore invalid follow cache member: {}", rawId);
            }
        }
        return ids.stream().sorted().collect(Collectors.toList());
    }

    private List<UserDTO> toUserDTOs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<User> users = userService.listByIds(ids);
        return users.stream()
                .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
    }

    private PageResult<UserDTO> toUserPage(List<Long> ids, int current, int size) {
        List<Long> sortedIds = ids == null ? Collections.emptyList() : ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        long total = sortedIds.size();
        long offset = (long) (current - 1) * size;
        if (offset >= total) {
            return PageResult.of(Collections.emptyList(), current, size, total, false, null);
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min(offset + size, total);
        List<UserDTO> users = toUserDTOs(sortedIds.subList(fromIndex, toIndex));
        return PageResult.of(users, current, size, total, toIndex < total, null);
    }

    private void removeUnfollowedAuthorFromFeed(Long userId, Long authorId) {
        try {
            List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>()
                    .select("id")
                    .eq("user_id", authorId)
                    .eq("status", BLOG_STATUS_PUBLISHED)
                    .eq("deleted", BLOG_NOT_DELETED)
                    .orderByDesc("publish_time")
                    .last("LIMIT " + feedCleanupLimit()));
            if (blogs == null || blogs.isEmpty()) {
                return;
            }
            Object[] blogIds = blogs.stream()
                    .map(Blog::getId)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toArray(String[]::new);
            if (blogIds.length > 0) {
                stringRedisTemplate.opsForZSet().remove(FEED_KEY + userId, blogIds);
            }
        } catch (RuntimeException e) {
            log.warn("remove unfollowed author from feed failed, userId={}, authorId={}", userId, authorId, e);
        }
    }

    private int feedCleanupLimit() {
        if (blogProperties == null || blogProperties.getFeedInboxMaxSize() <= 0) {
            return 1000;
        }
        return blogProperties.getFeedInboxMaxSize();
    }

    private void afterCommitOrNow(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private boolean isInvalidId(Long id) {
        return id == null || id <= 0;
    }

    private String followKey(Long userId) {
        return FOLLOW_KEY + userId;
    }

    private String loadedKey(Long userId) {
        return FOLLOW_LOADED_KEY + userId;
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
            log.warn("release follow cache lock failed", e);
        }
    }
}
