package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hmdp.entity.LoginLog;
import com.hmdp.mapper.LoginLogMapper;
import com.hmdp.security.RequestContextResolver;
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
    private static final String TOKEN_DIGEST_PREFIX = "sha256:";

    @Resource
    private LoginLogMapper loginLogMapper;

    @Resource
    private RequestContextResolver requestContextResolver;

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
                .setIp(requestContextResolver.getClientIp(request))
                .setUserAgent(RequestContextUtils.getUserAgent(request))
                .setTokenId(digestTokenId(tokenId))
                .setRiskLevel(0)
                .setFailCount(0)
                .setStatus(ENABLED);
    }

    private String digestTokenId(String tokenId) {
        if (StrUtil.isBlank(tokenId)) {
            return null;
        }
        if (tokenId.startsWith(TOKEN_DIGEST_PREFIX) && tokenId.length() == TOKEN_DIGEST_PREFIX.length() + 64) {
            return tokenId;
        }
        return TOKEN_DIGEST_PREFIX + DigestUtil.sha256Hex(tokenId);
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
