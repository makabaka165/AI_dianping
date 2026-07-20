package com.hmdp.ai.runtime.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WorkflowRuntimeConfiguration {
    @Bean("workflowNodeExecutor")
    public ThreadPoolTaskExecutor nodeExecutor() {
        return executor("workflow-node-", 4, 16, 200);
    }

    @Bean("workflowBranchExecutor")
    public ThreadPoolTaskExecutor branchExecutor() {
        return executor("workflow-branch-", 4, 16, 100);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
