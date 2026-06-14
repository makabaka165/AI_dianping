package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskEvent {
    private String taskId;
    private AiTaskStatus status;
    private Integer progressCurrent;
    private Integer progressTotal;
    private String errorMessage;
    private long timestampEpochMillis;
}
