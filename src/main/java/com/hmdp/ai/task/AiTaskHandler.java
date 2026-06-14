package com.hmdp.ai.task;

import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskType;

public interface AiTaskHandler {
    AiTaskType type();

    Object handle(AiTask task, AiTaskProgressReporter reporter) throws Exception;
}
