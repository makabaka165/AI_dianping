package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.LoginLog;
import com.hmdp.mapper.LoginLogMapper;
import com.hmdp.service.ILoginLogService;
import com.hmdp.utils.RequestContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

@Slf4j
@Service
public class LoginLogServiceImpl implements ILoginLogService {

    private static final int SUCCESS = 1;
    private static final int FAIL = 0;
    private static final int ENABLED = 1;

    @Resource
    private LoginLogMapper loginLogMapper;

    @Override
    public void recordLogin(Long userId, String phone, boolean success, String failReason, String tokenId) {
        recordLogin(userId, phone, success, failReason, tokenId, null, 0, 0);
    }

    @Override
    public void recordLogin(Long userId, String phone, boolean success, String failReason, String tokenId,
                            String deviceFingerprint, Integer riskLevel, Integer failCount) {
        LoginLog loginLog = buildBaseLog(userId, phone, success ? SUCCESS : FAIL, failReason, tokenId)
                .setAction("login")
                .setLoginType("sms_code")
                .setLoginTime(LocalDateTime.now())
                .setDeviceFingerprint(StrUtil.maxLength(deviceFingerprint, 128))
                .setRiskLevel(riskLevel == null ? 0 : riskLevel)
                .setFailCount(failCount == null ? 0 : failCount);
        insertQuietly(loginLog);
    }

    @Override
    public void recordRegister(Long userId, String phone) {
        LoginLog loginLog = buildBaseLog(userId, phone, SUCCESS, null, null)
                .setAction("register")
                .setLoginType("sms_code")
                .setLoginTime(LocalDateTime.now());
        insertQuietly(loginLog);
    }

    @Override
    public void recordLogout(Long userId, String tokenId) {
        LoginLog loginLog = buildBaseLog(userId, null, SUCCESS, null, tokenId)
                .setAction("logout")
                .setLogoutTime(LocalDateTime.now());
        insertQuietly(loginLog);
    }

    private LoginLog buildBaseLog(Long userId, String phone, Integer success, String failReason, String tokenId) {
        HttpServletRequest request = RequestContextUtils.currentRequest();
        return new LoginLog()
                .setUserId(userId)
                .setPhone(phone)
                .setSuccess(success)
                .setFailReason(failReason)
                .setIp(RequestContextUtils.getClientIp(request))
                .setUserAgent(RequestContextUtils.getUserAgent(request))
                .setTokenId(StrUtil.maxLength(tokenId, 128))
                .setRiskLevel(0)
                .setFailCount(0)
                .setStatus(ENABLED);
    }

    private void insertQuietly(LoginLog loginLog) {
        try {
            loginLogMapper.insert(loginLog);
        } catch (Exception e) {
            log.warn("写入登录审计日志失败: action={}, userId={}, reason={}",
                    loginLog.getAction(), loginLog.getUserId(), e.getMessage());
        }
    }
}
