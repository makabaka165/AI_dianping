package com.hmdp.ai.runtime.agent;

import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionContext;

public interface AgentExecutionEngine {
    AgentRunOutput execute(PublishedAgentDefinition definition, ExecutionContext context, AgentInputRequest input);
}
