package com.hmdp.service.impl;

import com.hmdp.common.ErrorCode;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.security.RequestContextResolver;
import com.hmdp.service.ILoginLogService;
import com.hmdp.service.IPermissionService;
import com.hmdp.service.sms.SmsCodeSender;
import com.hmdp.service.sms.SmsSendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_BLOCK_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_COOLDOWN_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_IP_MINUTE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_FAIL_COUNT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String PHONE = "13812341234";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private IPermissionService permissionService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ILoginLogService loginLogService;

    @Mock
    private RequestContextResolver requestContextResolver;

    @Mock
    private SmsCodeSender smsCodeSender;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "permissionService", permissionService);
        ReflectionTestUtils.setField(userService, "loginLogService", loginLogService);
        ReflectionTestUtils.setField(userService, "requestContextResolver", requestContextResolver);
        ReflectionTestUtils.setField(userService, "smsCodeSender", smsCodeSender);

        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sendCodeShouldUseResolverIpForRateLimitKeyAndNotExposeCodeByDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(requestContextResolver.getDeviceFingerprint(request)).thenReturn("device-hash");
        when(requestContextResolver.getClientIp(request)).thenReturn("resolver-ip");
        when(valueOperations.setIfAbsent(LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS))
                .thenReturn(true);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(smsCodeSender.sendLoginCode(eq(PHONE), anyString()))
                .thenReturn(SmsSendResult.submitted("sms code submitted"));

        Result result = userService.sendCode(PHONE, null, request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verify(valueOperations).set(eq(LOGIN_CODE_KEY + PHONE), anyString(), eq(LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        verify(smsCodeSender).sendLoginCode(eq(PHONE), anyString());

        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate, atLeastOnce()).execute(any(DefaultRedisScript.class), keysCaptor.capture(), anyString());
        assertThat(keysCaptor.getAllValues())
                .anySatisfy(keys -> assertThat(keys).containsExactly(LOGIN_CODE_IP_MINUTE_KEY + "resolver-ip"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendCodeShouldReturnMockVerifyCodeWhenSenderExposesIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(requestContextResolver.getDeviceFingerprint(request)).thenReturn("device-hash");
        when(requestContextResolver.getClientIp(request)).thenReturn("resolver-ip");
        when(valueOperations.setIfAbsent(LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS))
                .thenReturn(true);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(smsCodeSender.sendLoginCode(eq(PHONE), anyString()))
                .thenAnswer(invocation -> SmsSendResult.mock(invocation.getArgument(1), "mock sms code"));

        Result result = userService.sendCode(PHONE, null, request);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(LOGIN_CODE_KEY + PHONE), codeCaptor.capture(),
                eq(LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data.get("mock")).isEqualTo(true);
        assertThat(data.get("verifyCode")).isEqualTo(codeCaptor.getValue());
        assertThat(data.get("message")).isEqualTo("mock sms code");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mockVerifyCodeReturnedBySendCodeShouldBeAcceptedByLoginCaptchaCheck() {
        MockHttpServletRequest sendRequest = new MockHttpServletRequest();
        when(requestContextResolver.getDeviceFingerprint(sendRequest)).thenReturn("device-hash");
        when(requestContextResolver.getClientIp(sendRequest)).thenReturn("resolver-ip");
        when(valueOperations.setIfAbsent(LOGIN_CODE_COOLDOWN_KEY + PHONE, "1", 60L, TimeUnit.SECONDS))
                .thenReturn(true);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(smsCodeSender.sendLoginCode(eq(PHONE), anyString()))
                .thenAnswer(invocation -> SmsSendResult.mock(invocation.getArgument(1), "mock sms code"));

        Result sendResult = userService.sendCode(PHONE, null, sendRequest);
        String verifyCode = (String) ((Map<String, Object>) sendResult.getData()).get("verifyCode");

        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(loginRequest));
        when(requestContextResolver.getDeviceFingerprint(loginRequest)).thenReturn("resolver-device");
        when(userMapper.selectOne(any())).thenReturn(new User()
                .setId(7L)
                .setPhone(PHONE)
                .setNickName("user")
                .setStatus(0));

        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(PHONE);
        loginForm.setCode(verifyCode);

        Result loginResult = userService.login(loginForm, null);

        assertThat(loginResult.getSuccess()).isFalse();
        assertThat(loginResult.getCode()).isEqualTo(ErrorCode.ACCOUNT_DISABLED.getCode());

        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate, atLeastOnce()).execute(any(DefaultRedisScript.class), keysCaptor.capture(), argCaptor.capture());
        assertThat(keysCaptor.getAllValues())
                .anySatisfy(keys -> assertThat(keys).containsExactly(LOGIN_CODE_KEY + PHONE));
        assertThat(argCaptor.getAllValues()).contains(verifyCode);
    }

    @Test
    void loginFailureShouldUseResolverDeviceFingerprintForRiskKeys() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(requestContextResolver.getDeviceFingerprint(any(HttpServletRequest.class))).thenReturn("resolver-device");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);

        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(PHONE);
        loginForm.setCode("");

        Result result = userService.login(loginForm, null);

        assertThat(result.getSuccess()).isFalse();
        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate, times(2)).execute(any(DefaultRedisScript.class), keysCaptor.capture(), anyString());
        assertThat(keysCaptor.getAllValues()).containsExactly(
                List.of(LOGIN_FAIL_COUNT_KEY + "device:" + PHONE + ":resolver-device"),
                List.of(LOGIN_FAIL_COUNT_KEY + "phone:" + PHONE)
        );
    }

    @Test
    void loginFailureShouldBlockPhoneAfterGlobalLimitAcrossDeviceFingerprints() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(requestContextResolver.getDeviceFingerprint(any(HttpServletRequest.class))).thenReturn("rotated-user-agent");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L, 20L);

        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(PHONE);
        loginForm.setCode("");

        Result result = userService.login(loginForm, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo(ErrorCode.LOGIN_BLOCKED.getCode());
        verify(valueOperations).set(LOGIN_BLOCK_KEY + "phone:" + PHONE, "1", 15L, TimeUnit.MINUTES);
    }
}
