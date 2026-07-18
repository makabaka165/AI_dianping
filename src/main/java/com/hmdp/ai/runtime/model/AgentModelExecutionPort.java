package com.hmdp.ai.runtime.model;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;import com.hmdp.ai.domain.agent.PublishedAgentDefinition;import com.hmdp.ai.domain.run.*;
public interface AgentModelExecutionPort {AgentRunOutput execute(PublishedAgentDefinition definition,ExecutionContext context,AgentInputRequest input);}
