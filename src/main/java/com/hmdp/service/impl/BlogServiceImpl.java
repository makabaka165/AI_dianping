package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.config.BlogProperties;
import com.hmdp.dto.BlogCreateRequest;
import com.hmdp.dto.BlogDetailVO;
import com.hmdp.dto.BlogLikeUserVO;
import com.hmdp.dto.BlogListItemVO;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogLike;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.BlogImageOwnershipService;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_HOT_KEY;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.USER_BRIEF_KEY;
import static com.hmdp.utils.RedisConstants.USER_BRIEF_TTL;

@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private BlogLikeMapper blogLikeMapper;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private BlogProperties blogProperties;

    @Resource
    private BlogImageOwnershipService blogImageOwnershipService;

    @Resource
    private ShopMapper shopMapper;

    @Override
    public Result queryHotBlog(Integer current) {
        int pageNo = normalizePage(current);
        int pageSize = SystemConstants.MAX_PAGE_SIZE;
        long start = (long) (pageNo - 1) * pageSize;
        long end = start + pageSize - 1;

        List<Long> ids = queryHotIdsFromRedis(start, end);
        if (!ids.isEmpty()) {
            List<Blog> blogs = queryActiveBlogsByIds(ids);
            if (!blogs.isEmpty() && blogs.size() == ids.size()) {
                return Result.ok(toListItemVOs(blogs));
            }
            log.info("hot blog cache contains stale ids, requested={}, active={}", ids.size(), blogs.size());
        }

        Page<Blog> page = activeBlogQuery()
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .page(new Page<>(pageNo, pageSize));
        List<Blog> records = page.getRecords();
        warmHotBlogsCache();
        return Result.ok(toListItemVOs(records));
    }

    @Override
    public Result queryMyBlog(Integer current) {
        Long userId = currentUserService.requireCurrentUserId();
        Page<Blog> page = activeBlogQuery()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(normalizePage(current), SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(toListItemVOs(page.getRecords()));
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long userId) {
        if (userId == null || userId <= 0) {
            return Result.fail("userId is invalid");
        }
        Page<Blog> page = activeBlogQuery()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(normalizePage(current), SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(toListItemVOs(page.getRecords()));
    }

    @Override
    public Result queryBlogById(Long id) {
        if (isInvalidId(id)) {
            return Result.fail("blogId is invalid");
        }
        Blog blog = activeBlogQuery().eq("id", id).one();
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        return Result.ok(toDetailVO(blog, queryUserBriefs(Collections.singleton(blog.getUserId()))));
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        if (isInvalidId(id)) {
            return Result.fail("blogId is invalid");
        }
        Long userId = currentUserService.requireCurrentUserId();
        Blog blog = activeBlogQuery().select("id", "liked").eq("id", id).one();
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        BlogLike existed = queryBlogLike(id, userId);
        long eventTime = System.currentTimeMillis();
        if (existed == null) {
            BlogLike blogLike = new BlogLike()
                    .setBlogId(id)
                    .setUserId(userId)
                    .setCreateTime(LocalDateTime.now());
            try {
                blogLikeMapper.insert(blogLike);
                update().setSql("liked = COALESCE(liked, 0) + 1").eq("id", id).update();
                eventPublisher.publishEvent(new BlogLikeChangedEvent(id, userId, true, eventTime));
            } catch (DuplicateKeyException e) {
                log.info("ignore duplicate blog like, blogId={}, userId={}", id, userId);
            }
            return Result.ok();
        }

        int removed = blogLikeMapper.delete(new QueryWrapper<BlogLike>()
                .eq("blog_id", id)
                .eq("user_id", userId));
        if (removed > 0) {
            update().setSql("liked = GREATEST(COALESCE(liked, 0) - 1, 0)").eq("id", id).update();
            eventPublisher.publishEvent(new BlogLikeChangedEvent(id, userId, false, eventTime));
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (isInvalidId(id)) {
            return Result.fail("blogId is invalid");
        }
        if (!activeBlogExists(id)) {
            return Result.fail("blog not found");
        }
        List<Long> ids = queryLikeUserIdsFromRedis(id);
        if (ids.isEmpty()) {
            ids = queryLikeUserIdsFromMysql(id);
        }
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        Map<Long, UserDTO> userMap = queryUserBriefs(ids);
        List<BlogLikeUserVO> users = ids.stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .map(this::toLikeUserVO)
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    @Transactional
    public Result saveBlog(BlogCreateRequest request) {
        if (request == null || isInvalidId(request.getShopId())) {
            return Result.fail(ErrorCode.PARAM_ERROR, "shopId must be greater than 0");
        }
        if (!existsActiveShop(request.getShopId())) {
            return Result.fail(ErrorCode.SHOP_NOT_FOUND, "shop does not exist");
        }

        Long currentUserId = currentUserService.requireCurrentUserId();
        String normalizedImages = blogImageOwnershipService.validateAndNormalizeUserImages(request.getImages(), currentUserId);
        LocalDateTime now = LocalDateTime.now();
        Blog blog = new Blog()
                .setShopId(request.getShopId())
                .setUserId(currentUserId)
                .setTitle(request.getTitle())
                .setImages(normalizedImages)
                .setContent(request.getContent())
                .setLiked(0)
                .setComments(0)
                .setStatus(BLOG_STATUS_PUBLISHED)
                .setDeleted(BLOG_NOT_DELETED)
                .setPublishTime(now);

        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败!");
        }

        eventPublisher.publishEvent(new BlogPublishedEvent(
                blog.getId(),
                currentUserId,
                toEpochMilli(now)
        ));
        blogImageOwnershipService.refreshOwnerTtlForUserImages(normalizedImages, currentUserId);
        return Result.ok(blog.getId());
    }

    private boolean existsActiveShop(Long shopId) {
        Integer count = shopMapper.selectCount(new QueryWrapper<com.hmdp.entity.Shop>()
                .eq("id", shopId));
        return count != null && count > 0;
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = currentUserService.requireCurrentUserId();
        long maxTime = max == null || max <= 0 ? Long.MAX_VALUE : max;
        int sameTimeOffset = Math.max(offset == null ? 0 : offset, 0);
        Set<Long> currentFolloweeIds = queryCurrentFolloweeIdSet(userId);
        if (currentFolloweeIds.isEmpty()) {
            return Result.ok(emptyScrollResult(maxTime));
        }

        List<FeedCandidate> candidates = new ArrayList<>();
        candidates.addAll(queryInboxFeedCandidates(userId, maxTime));
        candidates.addAll(queryLargeAuthorFeedCandidates(userId, maxTime));
        if (candidates.isEmpty()) {
            return Result.ok(emptyScrollResult(maxTime));
        }

        List<FeedCandidate> sortedCandidates = candidates.stream()
                .collect(Collectors.toMap(FeedCandidate::getBlogId, c -> c, this::newerCandidate, LinkedHashMap::new))
                .values()
                .stream()
                .sorted(Comparator.comparingLong(FeedCandidate::getTime).reversed()
                        .thenComparing(FeedCandidate::getBlogId, Comparator.reverseOrder()))
                .collect(Collectors.toList());
        sortedCandidates = filterCandidatesByCurrentFollows(sortedCandidates, currentFolloweeIds);
        List<FeedCandidate> page = pageFeedCandidates(sortedCandidates, maxTime, sameTimeOffset);
        if (page.isEmpty()) {
            return Result.ok(emptyScrollResult(maxTime));
        }

        List<Long> ids = page.stream().map(FeedCandidate::getBlogId).collect(Collectors.toList());
        List<Blog> blogs = queryActiveBlogsByIds(ids);
        List<BlogListItemVO> vos = toListItemVOs(blogs);

        long minTime = page.stream().mapToLong(FeedCandidate::getTime).min().orElse(0);
        ScrollResult result = new ScrollResult();
        result.setList(vos);
        result.setOffset(nextOffset(minTime, maxTime, sameTimeOffset, page));
        result.setMinTime(minTime);
        return Result.ok(result);
    }

    private List<FeedCandidate> filterCandidatesByCurrentFollows(List<FeedCandidate> candidates, Set<Long> followeeIds) {
        if (candidates.isEmpty() || followeeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> blogIds = candidates.stream()
                .map(FeedCandidate::getBlogId)
                .collect(Collectors.toList());
        Map<Long, Long> blogAuthorMap = queryActiveBlogsByIds(blogIds)
                .stream()
                .collect(Collectors.toMap(Blog::getId, Blog::getUserId, (left, right) -> left));
        return candidates.stream()
                .filter(candidate -> followeeIds.contains(blogAuthorMap.get(candidate.getBlogId())))
                .collect(Collectors.toList());
    }

    private com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper<Blog> activeBlogQuery() {
        return query()
                .eq("status", BLOG_STATUS_PUBLISHED)
                .eq("deleted", BLOG_NOT_DELETED);
    }

    private List<FeedCandidate> pageFeedCandidates(List<FeedCandidate> candidates, long maxTime, int offset) {
        List<FeedCandidate> page = new ArrayList<>(blogProperties.getFeedPageSize());
        int skippedSameTime = 0;
        for (FeedCandidate candidate : candidates) {
            if (candidate.getTime() == maxTime && skippedSameTime < offset) {
                skippedSameTime++;
                continue;
            }
            page.add(candidate);
            if (page.size() >= blogProperties.getFeedPageSize()) {
                break;
            }
        }
        return page;
    }

    private int normalizePage(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }

    private boolean isInvalidId(Long id) {
        return id == null || id <= 0;
    }

    private boolean activeBlogExists(Long id) {
        return activeBlogQuery()
                .select("id")
                .eq("id", id)
                .one() != null;
    }

    private List<Long> queryHotIdsFromRedis(long start, long end) {
        try {
            Set<String> rawIds = stringRedisTemplate.opsForZSet().reverseRange(BLOG_HOT_KEY, start, end);
            if (rawIds == null || rawIds.isEmpty()) {
                return Collections.emptyList();
            }
            return rawIds.stream().map(Long::valueOf).collect(Collectors.toList());
        } catch (RuntimeException e) {
            log.warn("query hot blogs from redis failed", e);
            return Collections.emptyList();
        }
    }

    private void warmHotBlogsCache() {
        try {
            List<Blog> blogs = activeBlogQuery()
                    .select("id", "liked")
                    .orderByDesc("liked")
                    .orderByDesc("create_time")
                    .last("LIMIT " + blogProperties.getHotCacheSize())
                    .list();
            if (blogs.isEmpty()) {
                return;
            }
            stringRedisTemplate.delete(BLOG_HOT_KEY);
            blogs.forEach(blog -> stringRedisTemplate.opsForZSet()
                    .add(BLOG_HOT_KEY, blog.getId().toString(), scoreOf(blog)));
        } catch (RuntimeException e) {
            log.warn("warm hot blogs cache failed", e);
        }
    }

    private List<Blog> queryActiveBlogsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String idStr = StrUtil.join(",", ids);
        return activeBlogQuery()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
    }

    private BlogLike queryBlogLike(Long blogId, Long userId) {
        return blogLikeMapper.selectOne(new QueryWrapper<BlogLike>()
                .eq("blog_id", blogId)
                .eq("user_id", userId)
                .last("LIMIT 1"));
    }

    private boolean isBlogLiked(Long blogId) {
        Long userId = currentUserService.getCurrentUserId();
        if (userId == null) {
            return false;
        }

        String key = BLOG_LIKED_KEY + blogId;
        try {
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if (score != null) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("query blog liked status from redis failed, blogId={}, userId={}", blogId, userId, e);
        }

        BlogLike blogLike = queryBlogLike(blogId, userId);
        if (blogLike == null) {
            return false;
        }
        repairLikedZSetMember(blogId, blogLike);
        return true;
    }

    private List<Long> queryLikeUserIdsFromRedis(Long blogId) {
        try {
            Set<String> top = stringRedisTemplate.opsForZSet()
                    .range(BLOG_LIKED_KEY + blogId, 0, blogProperties.getLikeTopLimit() - 1);
            if (top == null || top.isEmpty()) {
                return Collections.emptyList();
            }
            return top.stream().map(Long::valueOf).collect(Collectors.toList());
        } catch (RuntimeException e) {
            log.warn("query blog likes from redis failed, blogId={}", blogId, e);
            return Collections.emptyList();
        }
    }

    private List<Long> queryLikeUserIdsFromMysql(Long blogId) {
        List<BlogLike> likes = blogLikeMapper.selectList(new QueryWrapper<BlogLike>()
                .eq("blog_id", blogId)
                .orderByAsc("create_time")
                .last("LIMIT " + blogProperties.getLikeTopLimit()));
        likes.forEach(like -> repairLikedZSetMember(blogId, like));
        return likes.stream().map(BlogLike::getUserId).collect(Collectors.toList());
    }

    private void repairLikedZSetMember(Long blogId, BlogLike blogLike) {
        try {
            stringRedisTemplate.opsForZSet().add(
                    BLOG_LIKED_KEY + blogId,
                    blogLike.getUserId().toString(),
                    toEpochMilli(blogLike.getCreateTime())
            );
        } catch (RuntimeException e) {
            log.warn("repair blog liked zset member failed, blogId={}, userId={}",
                    blogId, blogLike.getUserId(), e);
        }
    }

    private List<FeedCandidate> queryInboxFeedCandidates(Long userId, long maxTime) {
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples;
        try {
            tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, maxTime, 0, feedFetchSize());
        } catch (RuntimeException e) {
            log.warn("query inbox feed failed, userId={}", userId, e);
            return Collections.emptyList();
        }
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }
        return tuples.stream()
                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                .map(this::toFeedCandidate)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<FeedCandidate> queryLargeAuthorFeedCandidates(Long userId, long maxTime) {
        List<Long> largeAuthorIds = queryLargeFolloweeIds(userId);
        if (largeAuthorIds.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime maxPublishTime = maxTime == Long.MAX_VALUE
                ? LocalDateTime.now().plusYears(100)
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(maxTime), ZoneId.systemDefault());
        List<Blog> blogs = activeBlogQuery()
                .in("user_id", largeAuthorIds)
                .le("publish_time", maxPublishTime)
                .orderByDesc("publish_time")
                .orderByDesc("id")
                .last("LIMIT " + feedFetchSize())
                .list();
        return blogs.stream()
                .map(blog -> new FeedCandidate(blog.getId(), toEpochMilli(blog.getPublishTime())))
                .collect(Collectors.toList());
    }

    private List<Long> queryLargeFolloweeIds(Long userId) {
        List<Follow> follows = followService.query()
                .select("follow_user_id")
                .eq("user_id", userId)
                .list();
        if (follows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> followeeIds = follows.stream()
                .map(Follow::getFollowUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (followeeIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rows = followService.listMaps(new QueryWrapper<Follow>()
                .select("follow_user_id")
                .in("follow_user_id", followeeIds)
                .groupBy("follow_user_id")
                .having("COUNT(*) >= {0}", blogProperties.getLargeAuthorFansThreshold()));
        return rows.stream()
                .map(row -> row.get("follow_user_id"))
                .map(this::toLong)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Set<Long> queryCurrentFolloweeIdSet(Long userId) {
        return followService.query()
                .select("follow_user_id")
                .eq("user_id", userId)
                .list()
                .stream()
                .map(Follow::getFollowUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private int feedFetchSize() {
        return Math.max(
                blogProperties.getFeedPageSize(),
                blogProperties.getFeedPageSize() * blogProperties.getFeedFetchMultiplier()
        );
    }

    private FeedCandidate newerCandidate(FeedCandidate left, FeedCandidate right) {
        return left.getTime() >= right.getTime() ? left : right;
    }

    private int nextOffset(long minTime, long maxTime, int currentOffset, List<FeedCandidate> page) {
        int sameMinTimeCount = (int) page.stream()
                .filter(candidate -> candidate.getTime() == minTime)
                .count();
        return minTime == maxTime ? currentOffset + sameMinTimeCount : sameMinTimeCount;
    }

    private Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.valueOf((String) value);
            } catch (NumberFormatException e) {
                log.warn("ignore invalid long value: {}", value);
                return null;
            }
        }
        return null;
    }

    private FeedCandidate toFeedCandidate(ZSetOperations.TypedTuple<String> tuple) {
        try {
            return new FeedCandidate(Long.valueOf(tuple.getValue()), tuple.getScore().longValue());
        } catch (NumberFormatException e) {
            log.warn("ignore invalid feed member: {}", tuple.getValue());
            return null;
        }
    }

    private ScrollResult emptyScrollResult(long maxTime) {
        ScrollResult result = new ScrollResult();
        result.setList(Collections.emptyList());
        result.setOffset(0);
        result.setMinTime(maxTime == Long.MAX_VALUE ? 0L : maxTime);
        return result;
    }

    private List<BlogListItemVO> toListItemVOs(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, UserDTO> userMap = queryUserBriefs(blogs.stream()
                .map(Blog::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return blogs.stream()
                .map(blog -> toListItemVO(blog, userMap))
                .collect(Collectors.toList());
    }

    private BlogDetailVO toDetailVO(Blog blog, Map<Long, UserDTO> userMap) {
        BlogDetailVO vo = BeanUtil.copyProperties(blog, BlogDetailVO.class);
        fillUserAndLiked(vo, blog, userMap);
        return vo;
    }

    private BlogListItemVO toListItemVO(Blog blog, Map<Long, UserDTO> userMap) {
        BlogListItemVO vo = BeanUtil.copyProperties(blog, BlogListItemVO.class);
        fillUserAndLiked(vo, blog, userMap);
        return vo;
    }

    private void fillUserAndLiked(BlogListItemVO vo, Blog blog, Map<Long, UserDTO> userMap) {
        UserDTO user = userMap.get(blog.getUserId());
        if (user != null) {
            vo.setName(user.getNickName());
            vo.setIcon(user.getIcon());
        }
        vo.setIsLike(isBlogLiked(blog.getId()));
    }

    private BlogLikeUserVO toLikeUserVO(UserDTO user) {
        BlogLikeUserVO vo = new BlogLikeUserVO();
        vo.setId(user.getId());
        vo.setNickName(user.getNickName());
        vo.setIcon(user.getIcon());
        return vo;
    }

    private Map<Long, UserDTO> queryUserBriefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, UserDTO> result = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            UserDTO cached = queryUserBriefFromRedis(userId);
            if (cached == null) {
                missingIds.add(userId);
            } else {
                result.put(userId, cached);
            }
        }

        if (!missingIds.isEmpty()) {
            List<User> users = userService.listByIds(missingIds);
            for (User user : users) {
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                result.put(userDTO.getId(), userDTO);
                cacheUserBrief(userDTO);
            }
        }
        return result;
    }

    private UserDTO queryUserBriefFromRedis(Long userId) {
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(USER_BRIEF_KEY + userId);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            userDTO.setNickName((String) entries.get("nickName"));
            userDTO.setIcon((String) entries.get("icon"));
            return userDTO;
        } catch (RuntimeException e) {
            log.warn("query user brief cache failed, userId={}", userId, e);
            return null;
        }
    }

    private void cacheUserBrief(UserDTO userDTO) {
        try {
            Map<String, String> values = new HashMap<>();
            values.put("nickName", StrUtil.nullToEmpty(userDTO.getNickName()));
            values.put("icon", StrUtil.nullToEmpty(userDTO.getIcon()));
            String key = USER_BRIEF_KEY + userDTO.getId();
            stringRedisTemplate.opsForHash().putAll(key, values);
            stringRedisTemplate.expire(key, USER_BRIEF_TTL, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            log.warn("cache user brief failed, userId={}", userDTO.getId(), e);
        }
    }

    private double scoreOf(Blog blog) {
        return blog.getLiked() == null ? 0D : blog.getLiked().doubleValue();
    }

    private long toEpochMilli(LocalDateTime time) {
        LocalDateTime value = time == null ? LocalDateTime.now() : time;
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static class FeedCandidate {
        private final Long blogId;
        private final long time;

        private FeedCandidate(Long blogId, long time) {
            this.blogId = blogId;
            this.time = time;
        }

        public Long getBlogId() {
            return blogId;
        }

        public long getTime() {
            return time;
        }
    }
}
