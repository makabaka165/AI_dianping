package com.hmdp.ai.runtime.agent;

import com.hmdp.ai.application.agent.AgentRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AgentRuntimeRecoveryRunner {
    private final AgentRuntime runtime;
    private final AgentRuntimeProperties properties;

    public AgentRuntimeRecoveryRunner(AgentRuntime runtime, AgentRuntimeProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!properties.isRecoveryEnabled()) return;
        try {
            runtime.recover();
        } catch (Exception e) {
            log.error("agent run recovery failed without blocking application startup", e);
        }
    }
}
