package com.hmdp.ai.application.evaluation;

import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;

public interface EvaluationWorkflowRunner {
    AgentRunOutput execute(WorkflowDefinition workflow, PublishedAgentDefinition agent,
                           ExecutionContext context, AgentInputRequest input);
}
