package com.hmdp.ai.runtime.workflow;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;import com.hmdp.ai.domain.agent.PublishedAgentDefinition;import com.hmdp.ai.domain.run.*;import com.hmdp.ai.domain.workflow.WorkflowDefinition;
public interface WorkflowRuntime {AgentRunOutput execute(WorkflowDefinition workflow,PublishedAgentDefinition agent,ExecutionContext context,AgentInputRequest input);}
