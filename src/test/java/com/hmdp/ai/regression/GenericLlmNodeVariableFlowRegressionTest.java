package com.hmdp.ai.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.runtime.model.AgentModelExecutionPort;
import com.hmdp.ai.runtime.node.LlmNodeExecutor;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
class GenericLlmNodeVariableFlowRegressionTest {
    @Test
    void llmNodeMustBuildInputFromWorkflowVariables() {
        AtomicReference<AgentInputRequest> captured = new AtomicReference<>();
        AgentModelExecutionPort model = (definition, executionContext, input) -> {
            captured.set(input);
            return output();
        };
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("question", "What is the service quality?");
        variables.put("shopData", Collections.singletonMap("shopId", 7));
        NodeExecutionContext context = new NodeExecutionContext(null, null, null, null,
                variables, Collections.emptyList());

        new LlmNodeExecutor(model, new ObjectMapper()).execute(context);

        assertNotNull(captured.get());
        assertEquals("What is the service quality?", captured.get().getText());
    }

    private AgentRunOutput output() {
        return new AgentRunOutput("ok", Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), UsageSummary.empty(0), Collections.emptyList(), RunStatus.COMPLETED);
    }
}
