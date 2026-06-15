package com.hmdp.service.sms;

public interface SmsCodeSender {

    SmsSendResult sendLoginCode(String phone, String code);
}
