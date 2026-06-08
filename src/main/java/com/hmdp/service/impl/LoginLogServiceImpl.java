package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.LoginLog;
import com.hmdp.mapper.LoginLogMapper;
import com.hmdp.service.ILoginLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
        LoginLog loginLog = buildBaseLog(userId, phone, success ? SUCCESS : FAIL, failReason, tokenId)
                .setAction("login")
                .setLoginType("sms_code")
                .setLoginTime(LocalDateTime.now());
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
        HttpServletRequest request = currentRequest();
        return new LoginLog()
                .setUserId(userId)
                .setPhone(phone)
                .setSuccess(success)
                .setFailReason(failReason)
                .setIp(getClientIp(request))
                .setUserAgent(request == null ? null : StrUtil.maxLength(request.getHeader("User-Agent"), 512))
                .setTokenId(StrUtil.maxLength(tokenId, 128))
                .setRiskLevel(0)
                .setStatus(ENABLED);
    }

    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return null;
        }
        return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwardedFor) && !"unknown".equalsIgnoreCase(forwardedFor)) {
            return StrUtil.maxLength(forwardedFor.split(",")[0].trim(), 64);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return StrUtil.maxLength(realIp, 64);
        }
        return StrUtil.maxLength(request.getRemoteAddr(), 64);
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
