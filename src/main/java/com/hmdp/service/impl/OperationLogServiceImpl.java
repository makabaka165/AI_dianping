package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.OperationLog;
import com.hmdp.mapper.OperationLogMapper;
import com.hmdp.security.RequestContextResolver;
import com.hmdp.service.CurrentUserService;
import com.hmdp.service.IOperationLogService;
import com.hmdp.utils.RequestContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    @Resource
    private RequestContextResolver requestContextResolver;

    @Override
    public void record(String module, String operation, String targetType, String targetId,
                       String detail, boolean success, String failReason) {
        HttpServletRequest request = RequestContextUtils.currentRequest();
        OperationLog operationLog = new OperationLog()
                .setOperatorUserId(currentUserService.getCurrentUserId())
                .setModule(StrUtil.maxLength(module, 64))
                .setOperation(StrUtil.maxLength(operation, 64))
                .setTargetType(StrUtil.maxLength(targetType, 64))
                .setTargetId(StrUtil.maxLength(targetId, 128))
                .setDetail(StrUtil.maxLength(detail, 1000))
                .setSuccess(success ? 1 : 0)
                .setFailReason(StrUtil.maxLength(failReason, 255))
                .setIp(requestContextResolver.getClientIp(request))
                .setUserAgent(RequestContextUtils.getUserAgent(request))
                .setOperationTime(LocalDateTime.now());
        try {
            operationLogMapper.insert(operationLog);
        } catch (Exception e) {
            log.warn("写入操作审计失败: module={}, operation={}, reason={}", module, operation, e.getMessage());
        }
    }

    @Override
    public Page<OperationLog> pageLogs(Integer current, Integer size, String module, String operation,
                                       Long operatorUserId, Integer success) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null ? 10 : Math.min(Math.max(size, 1), 100);
        return operationLogMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new QueryWrapper<OperationLog>()
                        .eq(StrUtil.isNotBlank(module), "module", module)
                        .eq(StrUtil.isNotBlank(operation), "operation", operation)
                        .eq(operatorUserId != null && operatorUserId > 0, "operator_user_id", operatorUserId)
                        .eq(success != null, "success", success)
                        .orderByDesc("operation_time")
        );
    }
}
