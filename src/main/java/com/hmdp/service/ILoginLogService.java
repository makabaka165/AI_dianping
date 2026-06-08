package com.hmdp.service;

public interface ILoginLogService {

    void recordLogin(Long userId, String phone, boolean success, String failReason, String tokenId);

    void recordLogin(Long userId, String phone, boolean success, String failReason, String tokenId,
                     String deviceFingerprint, Integer riskLevel, Integer failCount);

    void recordRegister(Long userId, String phone);

    void recordLogout(Long userId, String tokenId);
}
