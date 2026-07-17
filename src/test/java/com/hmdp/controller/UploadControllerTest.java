package com.hmdp.controller;

import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.service.BlogImageOwnershipService;
import com.hmdp.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @TempDir
    Path tempDir;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private BlogImageOwnershipService blogImageOwnershipService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "currentUserService", currentUserService);
        ReflectionTestUtils.setField(controller, "blogImageOwnershipService", blogImageOwnershipService);
        ReflectionTestUtils.setField(controller, "imageUploadDir", tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @Test
    void uploadShouldRejectFileLargerThanFiveMb() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]);

        mockMvc.perform(multipart("/upload/blog").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void uploadShouldRejectJpgWithTextContent() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", "not an image".getBytes());

        mockMvc.perform(multipart("/upload/blog").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void deleteShouldRejectPathTraversalBeforeOwnershipCheck() throws Exception {
        mockMvc.perform(delete("/upload/blog").param("name", "../blogs/1/1/a.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void uploadShouldRemoveFileWhenOwnerRegistrationFails() throws Exception {
        byte[] pngHeader = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.png", "image/png", pngHeader);
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        doThrow(new IllegalStateException("redis down"))
                .when(blogImageOwnershipService).registerOwner(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(7L));

        mockMvc.perform(multipart("/upload/blog").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()));

        try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) {
            assertThat(paths.filter(Files::isRegularFile).count()).isZero();
        }
    }

    @Test
    void deleteShouldClearOwnershipOnlyAfterFileIsRemoved() throws Exception {
        Path image = tempDir.resolve("blogs/1/1/a.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1, 2, 3});
        when(currentUserService.requireCurrentUserId()).thenReturn(7L);
        when(blogImageOwnershipService.canDelete("blogs/1/1/a.png", 7L)).thenReturn(true);

        mockMvc.perform(delete("/upload/blog").param("name", "/blogs/1/1/a.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(image).doesNotExist();
        verify(blogImageOwnershipService).clearOwner("blogs/1/1/a.png");
    }
}
