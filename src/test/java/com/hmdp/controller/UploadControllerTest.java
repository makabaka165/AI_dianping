package com.hmdp.controller;

import com.hmdp.common.ErrorCode;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.service.BlogImageOwnershipService;
import com.hmdp.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

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
}
