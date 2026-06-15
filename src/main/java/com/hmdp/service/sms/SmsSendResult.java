package com.hmdp.service.sms;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SmsSendResult {

    private boolean mock;

    private boolean exposeCode;

    private String codeForDebug;

    private String message;

    public static SmsSendResult submitted(String message) {
        return new SmsSendResult(false, false, null, message);
    }

    public static SmsSendResult mock(String codeForDebug, String message) {
        return new SmsSendResult(true, true, codeForDebug, message);
    }
}
