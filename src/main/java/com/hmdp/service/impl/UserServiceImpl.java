package com.hmdp.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.security.RequestContextResolver;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.ILoginLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.IUserService;
import com.hmdp.service.sms.SmsCodeSender;
import com.hmdp.service.sms.SmsSendResult;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.RequestContextUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final int USER_STATUS_ENABLED = 1;
    private static final int USER_STATUS_DISABLED = 0;
    private static final int RISK_LOW = 0;
    private static final int RISK_MEDIUM = 1;
    private static final int RISK_HIGH = 2;

    private static final DefaultRedisScript<Long> VERIFY_CODE_SCRIPT;
    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRE_SCRIPT;

    static {
        VERIFY_CODE_SCRIPT = new DefaultRedisScript<>();
        VERIFY_CODE_SCRIPT.setResultType(Long.class);
        VERIFY_CODE_SCRIPT.setScriptText(
                "local code = redis.call('get', KEYS[1]); " +
                        "if not code then return 0; end; " +
                        "if code ~= ARGV[1] then return -1; end; " +
                        "redis.call('del', KEYS[1]); " +
                        "return 1;"
        );

        INCREMENT_WITH_EXPIRE_SCRIPT = new DefaultRedisScript<>();
        INCREMENT_WITH_EXPIRE_SCRIPT.setResultType(Long.class);
        INCREMENT_WITH_EXPIRE_SCRIPT.setScriptText(
                "local current = redis.call('incr', KEYS[1]); " +
                        "if current == 1 then redis.call('expire', KEYS[1], ARGV[1]); end; " +
                        "return current;"
        );
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IPermissionService permissionService;

    @Resource
    private ILoginLogService loginLogService;

    @Resource
    private CurrentUserService currentUserService;

    @Resource
    private RequestContextResolver requestContextResolver;

    @Resource
    private SmsCodeSender smsCodeSender;

    @Override
    public Result sendCode(String phone, HttpSession session, HttpServletRequest request) {
        String deviceFingerprint = requestContextResolver.getDeviceFingerprint(request);
        if (RegexUtils.isPhoneInvalid(phone)) {
            loginLogService.recordLogin(null, phone, false, "invalid phone when sending code", null,
                    deviceFingerprint, RISK_LOW, 0);
            return Result.fail(ErrorCode.PARAM_ERROR, "invalid phone");
        }

        Result rateLimitResult = checkSendCodeRateLimit(phone, requestContextResolver.getClientIp(request));
        if (rateLimitResult != null) {
            loginLogService.recordLogin(null, phone, false, "send code rate limited: " + rateLimitResult.getErrorMsg(),
                    null, deviceFingerprint, RISK_MEDIUM, 0);
            return rateLimitResult;
        }

        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        SmsSendResult sendResult = smsCodeSender.sendLoginCode(phone, code);
        if (sendResult.isExposeCode()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mock", sendResult.isMock());
            data.put("verifyCode", sendResult.getCodeForDebug());
            data.put("message", sendResult.getMessage());
            return Result.ok(data);
        }
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        HttpServletRequest request = RequestContextUtils.currentRequest();
        String deviceFingerprint = requestContextResolver.getDeviceFingerprint(request);

        if (RegexUtils.isPhoneInvalid(phone)) {
            loginLogService.recordLogin(null, phone, false, "invalid phone", null, deviceFingerprint, RISK_LOW, 0);
            return Result.fail(ErrorCode.PARAM_ERROR, "invalid phone");
        }

        Result blockResult = checkLoginBlocked(phone, deviceFingerprint);
        if (blockResult != null) {
            loginLogService.recordLogin(null, phone, false, "login blocked by risk control", null,
                    deviceFingerprint, RISK_HIGH, getFailCount(phone, deviceFingerprint));
            return blockResult;
        }

        String code = loginForm.getCode();
        if (StrUtil.isBlank(code)) {
            return recordCaptchaFailure(phone, deviceFingerprint, "empty captcha",
                    ErrorCode.CAPTCHA_ERROR, "captcha is required");
        }

        Long verifyResult = stringRedisTemplate.execute(
                VERIFY_CODE_SCRIPT,
                Collections.singletonList(LOGIN_CODE_KEY + phone),
                code
        );
        if (verifyResult == null || verifyResult == 0) {
            return recordCaptchaFailure(phone, deviceFingerprint, "captcha expired",
                    ErrorCode.CAPTCHA_EXPIRED, "captcha expired");
        }
        if (verifyResult < 0) {
            return recordCaptchaFailure(phone, deviceFingerprint, "captcha mismatch",
                    ErrorCode.CAPTCHA_ERROR, "captcha mismatch");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
            loginLogService.recordRegister(user.getId(), phone);
        }
        if (Integer.valueOf(USER_STATUS_DISABLED).equals(user.getStatus())) {
            loginLogService.recordLogin(user.getId(), phone, false, "account disabled", null,
                    deviceFingerprint, RISK_HIGH, getFailCount(phone, deviceFingerprint));
            return Result.fail(ErrorCode.ACCOUNT_DISABLED);
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        clearLoginFail(phone, deviceFingerprint);
        loginLogService.recordLogin(user.getId(), phone, true, null, token, deviceFingerprint, RISK_LOW, 0);
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        token = normalizeToken(token);
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        loginLogService.recordLogout(userId, token);
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        Long userId = currentUserService.requireCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = currentUserService.requireCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            }
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User()
                .setPhone(phone)
                .setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10))
                .setStatus(USER_STATUS_ENABLED);
        try {
            save(user);
            permissionService.assignDefaultBuyerRole(user.getId());
        } catch (DuplicateKeyException e) {
            User existingUser = query().eq("phone", phone).one();
            if (existingUser != null) {
                return existingUser;
            }
            throw e;
        }
        return user;
    }

    private Result checkSendCodeRateLimit(String phone, String clientIp) {
        String cooldownKey = LOGIN_CODE_COOLDOWN_KEY + phone;
        Boolean cooldownAllowed = stringRedisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", LOGIN_CODE_COOLDOWN_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(cooldownAllowed)) {
            Long ttl = stringRedisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS);
            return Result.fail(ErrorCode.RATE_LIMITED,
                    "verify code sent too frequently, retry after " + Math.max(ttl == null ? 0 : ttl, 1) + " seconds");
        }

        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String dailyKey = LOGIN_CODE_DAILY_KEY + day + ":" + phone;
        Long dailyCount = incrementWithExpire(dailyKey, 2, TimeUnit.DAYS);
        if (dailyCount > LOGIN_CODE_DAILY_LIMIT) {
            stringRedisTemplate.delete(cooldownKey);
            return Result.fail(ErrorCode.RATE_LIMITED, "daily verify code limit exceeded");
        }

        String ipMinuteKey = LOGIN_CODE_IP_MINUTE_KEY + clientIp;
        Long ipMinuteCount = incrementWithExpire(ipMinuteKey, 1, TimeUnit.MINUTES);
        if (ipMinuteCount > LOGIN_CODE_IP_MINUTE_LIMIT) {
            stringRedisTemplate.delete(cooldownKey);
            return Result.fail(ErrorCode.RATE_LIMITED, "network requests are too frequent");
        }
        return null;
    }

    private Result checkLoginBlocked(String phone, String deviceFingerprint) {
        String deviceBlockKey = loginDeviceBlockKey(phone, deviceFingerprint);
        String phoneBlockKey = loginPhoneBlockKey(phone);
        Boolean deviceBlocked = stringRedisTemplate.hasKey(deviceBlockKey);
        Boolean phoneBlocked = stringRedisTemplate.hasKey(phoneBlockKey);
        if (!Boolean.TRUE.equals(deviceBlocked) && !Boolean.TRUE.equals(phoneBlocked)) {
            return null;
        }
        long deviceTtl = ttlSeconds(deviceBlockKey, deviceBlocked);
        long phoneTtl = ttlSeconds(phoneBlockKey, phoneBlocked);
        return Result.fail(ErrorCode.LOGIN_BLOCKED,
                "login temporarily blocked, retry after " + Math.max(Math.max(deviceTtl, phoneTtl), 1) + " seconds");
    }

    private Result recordCaptchaFailure(String phone, String deviceFingerprint, String reason,
                                        ErrorCode errorCode, String message) {
        LoginFailureState failure = increaseLoginFail(phone, deviceFingerprint);
        int failCount = failure.failCount;
        loginLogService.recordLogin(null, phone, false, reason, null,
                deviceFingerprint, calcRiskLevel(failCount), failCount);
        if (failure.blocked) {
            return Result.fail(ErrorCode.LOGIN_BLOCKED,
                    "login temporarily blocked after too many failures");
        }
        return Result.fail(errorCode, message);
    }

    private LoginFailureState increaseLoginFail(String phone, String deviceFingerprint) {
        Long deviceCount = incrementWithExpire(loginDeviceFailCountKey(phone, deviceFingerprint),
                LOGIN_FAIL_WINDOW_MINUTES, TimeUnit.MINUTES);
        Long phoneCount = incrementWithExpire(loginPhoneFailCountKey(phone),
                LOGIN_FAIL_WINDOW_MINUTES, TimeUnit.MINUTES);
        int deviceFailCount = deviceCount == null ? 0 : deviceCount.intValue();
        int phoneFailCount = phoneCount == null ? 0 : phoneCount.intValue();
        boolean deviceBlocked = deviceFailCount >= LOGIN_FAIL_LIMIT;
        boolean phoneBlocked = phoneFailCount >= LOGIN_PHONE_FAIL_LIMIT;
        if (deviceBlocked) {
            stringRedisTemplate.opsForValue().set(loginDeviceBlockKey(phone, deviceFingerprint), "1",
                    LOGIN_BLOCK_MINUTES, TimeUnit.MINUTES);
        }
        if (phoneBlocked) {
            stringRedisTemplate.opsForValue().set(loginPhoneBlockKey(phone), "1",
                    LOGIN_BLOCK_MINUTES, TimeUnit.MINUTES);
        }
        return new LoginFailureState(Math.max(deviceFailCount, phoneFailCount), deviceBlocked || phoneBlocked);
    }

    private int getFailCount(String phone, String deviceFingerprint) {
        return Math.max(
                readFailCount(loginDeviceFailCountKey(phone, deviceFingerprint)),
                readFailCount(loginPhoneFailCountKey(phone))
        );
    }

    private int readFailCount(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void clearLoginFail(String phone, String deviceFingerprint) {
        stringRedisTemplate.delete(loginDeviceFailCountKey(phone, deviceFingerprint));
        stringRedisTemplate.delete(loginDeviceBlockKey(phone, deviceFingerprint));
        stringRedisTemplate.delete(loginPhoneFailCountKey(phone));
        stringRedisTemplate.delete(loginPhoneBlockKey(phone));
    }

    private int calcRiskLevel(int failCount) {
        if (failCount >= LOGIN_FAIL_LIMIT) {
            return RISK_HIGH;
        }
        if (failCount >= 3) {
            return RISK_MEDIUM;
        }
        return RISK_LOW;
    }

    private Long incrementWithExpire(String key, long timeout, TimeUnit unit) {
        long seconds = Math.max(unit.toSeconds(timeout), 1);
        Long count = stringRedisTemplate.execute(
                INCREMENT_WITH_EXPIRE_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(seconds)
        );
        return count == null ? 0 : count;
    }

    private long ttlSeconds(String key, Boolean present) {
        if (!Boolean.TRUE.equals(present)) {
            return 0L;
        }
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl == null ? 0L : ttl;
    }

    private String loginDeviceFailCountKey(String phone, String deviceFingerprint) {
        return LOGIN_FAIL_COUNT_KEY + "device:" + phone + ":" + deviceFingerprint;
    }

    private String loginPhoneFailCountKey(String phone) {
        return LOGIN_FAIL_COUNT_KEY + "phone:" + phone;
    }

    private String loginDeviceBlockKey(String phone, String deviceFingerprint) {
        return LOGIN_BLOCK_KEY + "device:" + phone + ":" + deviceFingerprint;
    }

    private String loginPhoneBlockKey(String phone) {
        return LOGIN_BLOCK_KEY + "phone:" + phone;
    }

    private String normalizeToken(String token) {
        if (StrUtil.isBlank(token)) {
            return token;
        }
        String trimmed = token.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private static class LoginFailureState {
        private final int failCount;
        private final boolean blocked;

        private LoginFailureState(int failCount, boolean blocked) {
            this.failCount = failCount;
            this.blocked = blocked;
        }
    }
}
