package com.hmdp.service;

public interface IOperationLogService {

    void record(String module, String operation, String targetType, String targetId,
                String detail, boolean success, String failReason);
}
