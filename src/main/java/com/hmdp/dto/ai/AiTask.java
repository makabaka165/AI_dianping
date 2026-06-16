package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTask {
    private String taskId;
    private AiTaskType type;
    private AiTaskStatus status;
    private String ownerUserId;
    private String dedupKey;
    private Map<String, Object> params;
    private Integer progressCurrent;
    private Integer progressTotal;
    private Object result;
    private String errorMessage;
    private Integer retryCount;
    private Long startedAtEpochMillis;
    private Long heartbeatAtEpochMillis;
    private Long finishedAtEpochMillis;
    private long createdAtEpochMillis;
    private long updatedAtEpochMillis;
}
