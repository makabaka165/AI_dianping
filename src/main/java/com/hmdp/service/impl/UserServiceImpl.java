package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.ILoginLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IPermissionService permissionService;

    @Resource
    private ILoginLogService loginLogService;

    @Resource
    private CurrentUserService currentUserService;

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

    @Override
    public Result sendCode(String phone, HttpSession session, HttpServletRequest request) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            loginLogService.recordLogin(null, phone, false, "发送验证码手机号格式错误", null);
            return Result.fail("手机号格式错误！");
        }
        Result rateLimitResult = checkSendCodeRateLimit(phone, getClientIp(request));
        if (rateLimitResult != null) {
            loginLogService.recordLogin(null, phone, false, "发送验证码限流：" + rateLimitResult.getErrorMsg(), null);
            return rateLimitResult;
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        // 当前项目没有接入真实短信服务，保留日志输出用于本地测试；生产环境应替换为短信服务并关闭明文日志。
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            loginLogService.recordLogin(null, phone, false, "手机号格式错误", null);
            return Result.fail("手机号格式错误！");
        }
        // 3.从redis获取验证码并校验
        String code = loginForm.getCode();
        if (StrUtil.isBlank(code)) {
            loginLogService.recordLogin(null, phone, false, "验证码为空", null);
            return Result.fail("验证码不能为空");
        }

        String codeKey = LOGIN_CODE_KEY + phone;
        Long verifyResult = stringRedisTemplate.execute(
                VERIFY_CODE_SCRIPT,
                Collections.singletonList(codeKey),
                code
        );
        if (verifyResult == null || verifyResult == 0) {
            loginLogService.recordLogin(null, phone, false, "验证码已过期", null);
            return Result.fail("验证码已过期，请重新获取");
        }
        if (verifyResult < 0) {
            loginLogService.recordLogin(null, phone, false, "验证码错误", null);
            return Result.fail("验证码错误");
        }

        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
            loginLogService.recordRegister(user.getId(), phone);
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        loginLogService.recordLogin(user.getId(), phone, true, null, token);

        // 8.返回token
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
        // 1.获取当前登录用户
        Long userId = currentUserService.requireCurrentUserId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = currentUserService.requireCurrentUserId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
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
            return Result.fail("验证码发送过于频繁，请" + Math.max(ttl == null ? 0 : ttl, 1) + "秒后再试");
        }

        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String dailyKey = LOGIN_CODE_DAILY_KEY + day + ":" + phone;
        Long dailyCount = incrementWithExpire(dailyKey, 2, TimeUnit.DAYS);
        if (dailyCount > LOGIN_CODE_DAILY_LIMIT) {
            stringRedisTemplate.delete(cooldownKey);
            return Result.fail("今日验证码发送次数已达上限，请明天再试");
        }

        String ipMinuteKey = LOGIN_CODE_IP_MINUTE_KEY + clientIp;
        Long ipMinuteCount = incrementWithExpire(ipMinuteKey, 1, TimeUnit.MINUTES);
        if (ipMinuteCount > LOGIN_CODE_IP_MINUTE_LIMIT) {
            stringRedisTemplate.delete(cooldownKey);
            return Result.fail("当前网络环境请求过于频繁，请稍后再试");
        }
        return null;
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

    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwardedFor) && !"unknown".equalsIgnoreCase(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp;
        }
        return request.getRemoteAddr();
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
}
