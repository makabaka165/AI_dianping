package com.hmdp.ai.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.domain.run.RunStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAgentIdentityConsistencyRegressionTest {
    @Test
    void runResponseMustExposeStableDefinitionAndCodeIdentity() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new AgentRunCreatedResponse("run", RunStatus.QUEUED, "agent", 1));
        assertTrue(json.contains("agentDefinitionId"));
        assertTrue(json.contains("agentCode"));
    }
}
