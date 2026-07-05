package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.BlogCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.exception.BusinessException;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.BlogImageOwnershipService;
import com.hmdp.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogServiceImplTest {

    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ShopMapper shopMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private BlogImageOwnershipService blogImageOwnershipService;

    private TestableBlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TestableBlogServiceImpl();
        ReflectionTestUtils.setField(service, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(service, "blogImageOwnershipService", blogImageOwnershipService);
    }

    @Test
    void saveBlogWhenShopIdNullOrNegativeShouldReturnParamError() {
        BlogCreateRequest nullShop = request(null);
        BlogCreateRequest negativeShop = request(-1L);

        Result nullResult = service.saveBlog(nullShop);
        Result negativeResult = service.saveBlog(negativeShop);

        assertThat(nullResult.getSuccess()).isFalse();
        assertThat(nullResult.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(negativeResult.getSuccess()).isFalse();
        assertThat(negativeResult.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(service.savedBlogs).isEmpty();
        verifyNoInteractions(shopMapper, currentUserService, eventPublisher, blogImageOwnershipService);
    }

    @Test
    void saveBlogWhenShopNotExistsShouldReturnShopNotFoundAndNotSaveOrPublish() {
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(0);

        Result result = service.saveBlog(request(99L));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.SHOP_NOT_FOUND.getCode());
        assertThat(service.savedBlogs).isEmpty();
        verifyNoInteractions(currentUserService, eventPublisher, blogImageOwnershipService);
    }

    @Test
    void saveBlogWhenShopExistsShouldSaveBlogPublishEventAndRefreshImageOwnerTtl() {
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(blogImageOwnershipService.validateAndNormalizeUserImages("/imgs/a.jpg", 7L))
                .thenReturn("blogs/a.jpg");

        Result result = service.saveBlog(request(1L));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(21L);
        assertThat(service.savedBlogs).hasSize(1);
        Blog saved = service.savedBlogs.get(0);
        assertThat(saved.getShopId()).isEqualTo(1L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getImages()).isEqualTo("blogs/a.jpg");
        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(saved.getDeleted()).isEqualTo(0);
        assertThat(saved.getPublishTime()).isNotNull();

        ArgumentCaptor<BlogPublishedEvent> eventCaptor = ArgumentCaptor.forClass(BlogPublishedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBlogId()).isEqualTo(21L);
        assertThat(eventCaptor.getValue().getAuthorId()).isEqualTo(7L);
        verify(blogImageOwnershipService).refreshOwnerTtlForUserImages(eq("blogs/a.jpg"), eq(7L));
    }

    @Test
    void saveBlogWhenImageOwnershipInvalidShouldRejectBeforeSave() {
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(blogImageOwnershipService.validateAndNormalizeUserImages("/imgs/a.jpg", 7L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "blog image is not owned by current user"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveBlog(request(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("blog image is not owned by current user");
        assertThat(service.savedBlogs).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
        verify(blogImageOwnershipService, never()).refreshOwnerTtlForUserImages(any(), any());
    }

    @Test
    void saveBlogWhenDbSaveFailsShouldNotPublishEventOrRefreshImageOwnerTtl() {
        service.saveResult = false;
        when(shopMapper.selectCount(any(QueryWrapper.class))).thenReturn(1);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(blogImageOwnershipService.validateAndNormalizeUserImages("/imgs/a.jpg", 7L))
                .thenReturn("blogs/a.jpg");

        Result result = service.saveBlog(request(1L));

        assertThat(result.getSuccess()).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
        verify(blogImageOwnershipService, never()).refreshOwnerTtlForUserImages(any(), any());
    }

    private BlogCreateRequest request(Long shopId) {
        BlogCreateRequest request = new BlogCreateRequest();
        request.setShopId(shopId);
        request.setTitle("title");
        request.setImages("/imgs/a.jpg");
        request.setContent("content");
        return request;
    }

    private static class TestableBlogServiceImpl extends BlogServiceImpl {
        private final java.util.List<Blog> savedBlogs = new java.util.ArrayList<>();
        private boolean saveResult = true;

        @Override
        public boolean save(Blog entity) {
            if (!saveResult) {
                return false;
            }
            entity.setId(21L);
            savedBlogs.add(entity);
            return true;
        }
    }
}
