package com.hmdp.service.impl;

import com.hmdp.entity.Blog;
import com.hmdp.exception.BusinessException;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogImageOwnershipServiceTest {

    private static final String PATH = "blogs/1/1/a.png";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private IPermissionService permissionService;

    private BlogImageOwnershipServiceImpl ownershipService;

    @BeforeEach
    void setUp() {
        ownershipService = new BlogImageOwnershipServiceImpl();
        ReflectionTestUtils.setField(ownershipService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(ownershipService, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(ownershipService, "permissionService", permissionService);
        ReflectionTestUtils.setField(ownershipService, "ownerTtlDays", 7L);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void registerOwnerShouldWriteNormalizedPathOwnerWithTtl() {
        ownershipService.registerOwner(PATH, 1L);

        verify(valueOperations).set(ownershipService.ownerKey(PATH), "1", 7L, TimeUnit.DAYS);
    }

    @Test
    void ownerCanDeleteOwnImage() {
        when(permissionService.hasRole(1L, "admin")).thenReturn(false);
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn("1");

        assertThat(ownershipService.canDelete(PATH, 1L)).isTrue();
    }

    @Test
    void differentUserCannotDeleteOwnedImage() {
        when(permissionService.hasRole(2L, "admin")).thenReturn(false);
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn("1");

        assertThat(ownershipService.canDelete(PATH, 2L)).isFalse();
        verify(blogMapper, never()).selectList(any());
    }

    @Test
    void adminCanDeleteAnyImage() {
        when(permissionService.hasRole(9L, "admin")).thenReturn(true);

        assertThat(ownershipService.canDelete(PATH, 9L)).isTrue();
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void legacyImageBoundToOwnBlogCanBeDeletedWhenOwnerMissing() {
        when(permissionService.hasRole(1L, "admin")).thenReturn(false);
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn(null);
        Blog blog = new Blog().setUserId(1L).setImages("/blogs/1/1/a.png,blogs/2/2/b.png");
        when(blogMapper.selectList(any())).thenReturn(List.of(blog));

        assertThat(ownershipService.canDelete(PATH, 1L)).isTrue();
    }

    @Test
    void legacyCsvBadSegmentShouldNotInvalidateOtherValidSegments() {
        when(permissionService.hasRole(1L, "admin")).thenReturn(false);
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn(null);
        Blog blog = new Blog().setUserId(1L)
                .setImages("https://example.com/bad.png,/blogs/1/1/a.png,../bad.png");
        when(blogMapper.selectList(any())).thenReturn(List.of(blog));

        assertThat(ownershipService.canDelete(PATH, 1L)).isTrue();
    }

    @Test
    void validateAndNormalizeShouldAllowCurrentOwnerAndReturnNormalizedCsv() {
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn("1");

        String normalized = ownershipService.validateAndNormalizeUserImages("/imgs/" + PATH, 1L);

        assertThat(normalized).isEqualTo(PATH);
    }

    @Test
    void validateAndNormalizeShouldAllowLegacyImageBoundToOwnBlog() {
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn(null);
        Blog blog = new Blog().setUserId(1L).setImages("/blogs/1/1/a.png");
        when(blogMapper.selectList(any())).thenReturn(List.of(blog));

        String normalized = ownershipService.validateAndNormalizeUserImages(PATH, 1L);

        assertThat(normalized).isEqualTo(PATH);
    }

    @Test
    void validateAndNormalizeShouldRejectExternalOrOtherUserImage() {
        assertThatThrownBy(() -> ownershipService.validateAndNormalizeUserImages("https://example.com/a.png", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("blog image path is invalid");

        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn("2");
        when(blogMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> ownershipService.validateAndNormalizeUserImages(PATH, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("blog image is not owned by current user");
    }

    @Test
    void substringMatchShouldNotGrantLegacyPermission() {
        when(permissionService.hasRole(1L, "admin")).thenReturn(false);
        when(valueOperations.get(ownershipService.ownerKey(PATH))).thenReturn(null);
        Blog blog = new Blog().setUserId(1L).setImages("blogs/1/1/a.png.evil,other/blogs/1/1/a.png");
        when(blogMapper.selectList(any())).thenReturn(List.of(blog));

        assertThat(ownershipService.canDelete(PATH, 1L)).isFalse();
    }

    @Test
    void clearOwnerShouldDeleteOwnershipKey() {
        ownershipService.clearOwner(PATH);

        verify(stringRedisTemplate).delete(ownershipService.ownerKey(PATH));
    }
}
