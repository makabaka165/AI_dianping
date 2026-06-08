package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.OperationLog;
import com.hmdp.mapper.OperationLogMapper;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IOperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

@Slf4j
@Service
public class OperationLogServiceImpl implements IOperationLogService {

    @Resource
    private OperationLogMapper operationLogMapper;

    @Resource
    private CurrentUserService currentUserService;

    @Override
    public void record(String module, String operation, String targetType, String targetId,
                       String detail, boolean success, String failReason) {
        HttpServletRequest request = currentRequest();
        OperationLog operationLog = new OperationLog()
                .setOperatorUserId(currentUserService.getCurrentUserId())
                .setModule(StrUtil.maxLength(module, 64))
                .setOperation(StrUtil.maxLength(operation, 64))
                .setTargetType(StrUtil.maxLength(targetType, 64))
                .setTargetId(StrUtil.maxLength(targetId, 128))
                .setDetail(StrUtil.maxLength(detail, 1000))
                .setSuccess(success ? 1 : 0)
                .setFailReason(StrUtil.maxLength(failReason, 255))
                .setIp(getClientIp(request))
                .setUserAgent(request == null ? null : StrUtil.maxLength(request.getHeader("User-Agent"), 512))
                .setOperationTime(LocalDateTime.now());
        try {
            operationLogMapper.insert(operationLog);
        } catch (Exception e) {
            log.warn("写入操作审计失败: module={}, operation={}, reason={}", module, operation, e.getMessage());
        }
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
}
