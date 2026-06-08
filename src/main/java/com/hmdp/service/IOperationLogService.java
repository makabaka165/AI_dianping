package com.hmdp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.OperationLog;

public interface IOperationLogService {

    void record(String module, String operation, String targetType, String targetId,
                String detail, boolean success, String failReason);

    Page<OperationLog> pageLogs(Integer current, Integer size, String module, String operation,
                                Long operatorUserId, Integer success);
}
