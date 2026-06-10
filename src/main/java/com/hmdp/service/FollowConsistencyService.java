package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_CACHE_TTL;
import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_LOADED_KEY;

@Slf4j
@Service
public class FollowConsistencyService {

    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;

    @Resource
    private FollowMapper followMapper;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Map<String, Object> rebuildFollowCache(Long userId) {
        validateUserId(userId);
        List<Long> followeeIds = queryFolloweeIds(userId);
        rebuildUserFollowCache(userId, followeeIds);

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("followCount", followeeIds.size());
        result.put("rebuiltUsers", 1);
        return result;
    }

    public Map<String, Object> rebuildAllFollowCaches() {
        List<Follow> follows = followMapper.selectList(new QueryWrapper<Follow>()
                .select("user_id", "follow_user_id"));
        Map<Long, List<Long>> followeeIdsByUser = new LinkedHashMap<>();
        for (Follow follow : follows) {
            if (follow.getUserId() == null || follow.getFollowUserId() == null) {
                continue;
            }
            followeeIdsByUser.computeIfAbsent(follow.getUserId(), ignored -> new ArrayList<>())
                    .add(follow.getFollowUserId());
        }

        int rebuiltRelations = 0;
        for (Map.Entry<Long, List<Long>> entry : followeeIdsByUser.entrySet()) {
            List<Long> followeeIds = entry.getValue();
            rebuildUserFollowCache(entry.getKey(), followeeIds);
            rebuiltRelations += followeeIds.size();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("rebuiltUsers", followeeIdsByUser.size());
        result.put("rebuiltRelations", rebuiltRelations);
        return result;
    }

    public Map<String, Object> repairUserFeed(Long userId) {
        validateUserId(userId);
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .rangeWithScores(feedKey(userId), 0, -1);
        int scanned = tuples == null ? 0 : tuples.size();
        if (tuples == null || tuples.isEmpty()) {
            return feedRepairResult(userId, scanned, 0);
        }

        Set<Long> followeeIds = new HashSet<>(queryFolloweeIds(userId));
        if (followeeIds.isEmpty()) {
            stringRedisTemplate.delete(feedKey(userId));
            return feedRepairResult(userId, scanned, scanned);
        }

        Map<Long, String> blogIdToMember = new LinkedHashMap<>();
        List<String> staleMembers = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            if (member == null) {
                continue;
            }
            Long blogId = toLong(member);
            if (blogId == null) {
                staleMembers.add(member);
                continue;
            }
            blogIdToMember.put(blogId, member);
        }

        Map<Long, Long> activeBlogAuthors = queryActiveBlogAuthors(blogIdToMember.keySet());
        for (Map.Entry<Long, String> entry : blogIdToMember.entrySet()) {
            Long authorId = activeBlogAuthors.get(entry.getKey());
            if (authorId == null || !followeeIds.contains(authorId)) {
                staleMembers.add(entry.getValue());
            }
        }

        if (!staleMembers.isEmpty()) {
            stringRedisTemplate.opsForZSet().remove(feedKey(userId), staleMembers.toArray());
        }
        return feedRepairResult(userId, scanned, staleMembers.size());
    }

    private List<Long> queryFolloweeIds(Long userId) {
        return followMapper.selectList(new QueryWrapper<Follow>()
                        .select("follow_user_id")
                        .eq("user_id", userId))
                .stream()
                .map(Follow::getFollowUserId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private void rebuildUserFollowCache(Long userId, List<Long> followeeIds) {
        String key = followKey(userId);
        stringRedisTemplate.delete(key);
        if (followeeIds != null && !followeeIds.isEmpty()) {
            String[] members = followeeIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .map(String::valueOf)
                    .toArray(String[]::new);
            if (members.length > 0) {
                stringRedisTemplate.opsForSet().add(key, members);
                stringRedisTemplate.expire(key, FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
            }
        }
        stringRedisTemplate.opsForValue()
                .set(loadedKey(userId), "1", FOLLOW_CACHE_TTL, TimeUnit.MINUTES);
    }

    private Map<Long, Long> queryActiveBlogAuthors(Set<Long> blogIds) {
        if (blogIds == null || blogIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>()
                .select("id", "user_id")
                .in("id", blogIds)
                .eq("status", BLOG_STATUS_PUBLISHED)
                .eq("deleted", BLOG_NOT_DELETED));
        Map<Long, Long> authors = new HashMap<>();
        for (Blog blog : blogs) {
            if (blog.getId() != null && blog.getUserId() != null) {
                authors.put(blog.getId(), blog.getUserId());
            }
        }
        return authors;
    }

    private Map<String, Object> feedRepairResult(Long userId, int scanned, int removed) {
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("scannedFeedItems", scanned);
        result.put("removedFeedItems", removed);
        return result;
    }

    private Long toLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("ignore invalid feed member: {}", value);
            return null;
        }
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("user id is invalid");
        }
    }

    private String followKey(Long userId) {
        return FOLLOW_KEY + userId;
    }

    private String loadedKey(Long userId) {
        return FOLLOW_LOADED_KEY + userId;
    }

    private String feedKey(Long userId) {
        return FEED_KEY + userId;
    }
}
