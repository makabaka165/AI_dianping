package com.hmdp.config;

import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebExceptionAdviceTest {

    private final WebExceptionAdvice advice = new WebExceptionAdvice();

    @Test
    void illegalArgumentExceptionShouldReturnParameterErrorWithoutSensitiveMessage() {
        Result result = advice.handleIllegalArgumentException(new IllegalArgumentException(
                "provider=openai apiKey=sk-test-secret SQL=select * from user at com.hmdp.Service"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(result.getErrorMsg()).isEqualTo(ErrorCode.PARAM_ERROR.getMessage());
        assertThat(result.getErrorMsg())
                .doesNotContain("openai")
                .doesNotContain("sk-")
                .doesNotContain("select")
                .doesNotContain("at com.");
    }

    @Test
    void illegalArgumentExceptionShouldKeepOrdinaryParameterMessage() {
        Result result = advice.handleIllegalArgumentException(new IllegalArgumentException("shopId must be positive"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(result.getErrorMsg()).isEqualTo("shopId must be positive");
    }

    @Test
    void illegalStateExceptionShouldReturnSystemErrorWithoutInternalMessage() {
        Result result = advice.handleIllegalStateException(
                new IllegalStateException("Read AI task failed: redis.internal:6379"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(result.getErrorMsg()).isEqualTo(ErrorCode.SYSTEM_ERROR.getMessage());
        assertThat(result.getErrorMsg()).doesNotContain("redis.internal");
    }
}
