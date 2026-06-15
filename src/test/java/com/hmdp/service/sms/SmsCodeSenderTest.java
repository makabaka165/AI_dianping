package com.hmdp.service.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsCodeSenderTest {

    @Test
    void noopSenderShouldNotExposeCode() {
        SmsSendResult result = new NoopSmsCodeSender().sendLoginCode("13812341234", "123456");

        assertThat(result.isMock()).isFalse();
        assertThat(result.isExposeCode()).isFalse();
        assertThat(result.getCodeForDebug()).isNull();
    }

    @Test
    void mockSenderShouldExposeCodeOnlyInResult() {
        SmsSendResult result = new MockSmsCodeSender().sendLoginCode("13812341234", "123456");

        assertThat(result.isMock()).isTrue();
        assertThat(result.isExposeCode()).isTrue();
        assertThat(result.getCodeForDebug()).isEqualTo("123456");
    }
}
