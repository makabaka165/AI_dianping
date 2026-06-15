package com.hmdp.service.sms;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "hmdp.sms.mock", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopSmsCodeSender implements SmsCodeSender {

    @Override
    public SmsSendResult sendLoginCode(String phone, String code) {
        log.info("login sms code submitted, phone={}", maskPhone(phone));
        return SmsSendResult.submitted("sms code submitted");
    }

    private String maskPhone(String phone) {
        if (StrUtil.isBlank(phone) || phone.length() < 7) {
            return "unknown";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
