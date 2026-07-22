package com.hmdp.ai.integration;

import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MultiLlmNodeVariableFlowIntegrationTest {
    @Test
    void passesFirstModelOutputToSecondVersionedPromptAndRecordsBothCalls() {
        PromptVersion firstPrompt = RuntimeIntegrationTestSupport.prompt("prompt-one", 1,
                "You create a draft.", "Draft for {{text}}",
                "{\"type\":\"object\",\"required\":[\"text\"]}");
        PromptVersion secondPrompt = RuntimeIntegrationTestSupport.prompt("prompt-two", 2,
                "You refine an existing draft.", "Refine this draft: {{draft}}",
                "{\"type\":\"object\",\"required\":[\"draft\"]}");
        ModelProfileVersion model = RuntimeIntegrationTestSupport.modelProfileVersion();
        PublishedAgentDefinition agent = RuntimeIntegrationTestSupport.agent(
                "research-assistant", firstPrompt, model, "workflow-version");
        RuntimeIntegrationTestSupport.FixedPromptRepository prompts =
                new RuntimeIntegrationTestSupport.FixedPromptRepository(firstPrompt, secondPrompt);
        AtomicInteger calls = new AtomicInteger();

        try (RuntimeIntegrationTestSupport.WorkflowHarness harness =
                     RuntimeIntegrationTestSupport.workflowHarness(prompts, model, invocation -> {
                         int call = calls.incrementAndGet();
                         String content = call == 1 ? "draft answer" : "final answer";
                         return new ModelInvocationResult(content, null, 10 + call, 4 + call,
                                 false, 5, new BigDecimal("0.001"), "provider-call-" + call);
                     })) {
            WorkflowDefinition workflow = workflow();
            AgentInputRequest input = new AgentInputRequest();
            input.setText("Explain resilient indexing");
            ExecutionContext context = new ExecutionContext(RuntimeIntegrationTestSupport.TENANT,
                    RuntimeIntegrationTestSupport.WORKSPACE, "user", "session", "conversation", "run-multi",
                    agent.getAgent().getId(), agent.getVersion().getVersion(), "en-US", "UTC",
                    Collections.emptyList(), Collections.emptyList(),
                    new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                    Instant.now().plusSeconds(30), Collections.emptyMap(), "trace-multi");

            AgentRunOutput output = harness.runtime.execute(workflow, agent, context, input);

            assertThat(output.getAnswer()).isEqualTo("final answer");
            assertThat(harness.modelCalls.failures).isEmpty();
            assertThat(harness.modelCalls.invocations).hasSize(2);
            assertThat(harness.modelCalls.invocations.get(0).getSystemPrompt())
                    .isEqualTo("You create a draft.");
            assertThat(harness.modelCalls.invocations.get(1).getSystemPrompt())
                    .isEqualTo("You refine an existing draft.");
            assertThat(harness.modelCalls.invocations.get(1).getUserPrompt()).contains("draft answer");
            assertThat(harness.modelCalls.invocations)
                    .extracting(value -> value.getContext().getInvocationContext().getNodeRunId())
                    .doesNotContainNull()
                    .doesNotHaveDuplicates();
            assertThat(harness.nodeRuns.starts.stream().map(value -> value.nodeId)
                    .collect(Collectors.toList())).contains("first-llm", "second-llm");
        }
    }

    private WorkflowDefinition workflow() {
        return RuntimeIntegrationTestSupport.workflow("workflow-version", Arrays.asList(
                        RuntimeIntegrationTestSupport.node("start", WorkflowNodeType.START, "{}"),
                        RuntimeIntegrationTestSupport.node("first-llm", WorkflowNodeType.LLM,
                                "{\"useAgentDefaultPrompt\":false,"
                                        + "\"promptVersionId\":\"prompt-one\","
                                        + "\"inputMapping\":{\"text\":\"$.text\"},"
                                        + "\"outputVariable\":\"draft\",\"responseFormat\":\"TEXT\"}"),
                        RuntimeIntegrationTestSupport.node("second-llm", WorkflowNodeType.LLM,
                                "{\"useAgentDefaultPrompt\":false,"
                                        + "\"promptVersionId\":\"prompt-two\","
                                        + "\"inputMapping\":{\"draft\":\"$.draft\"},"
                                        + "\"outputVariable\":\"agentOutput\",\"responseFormat\":\"TEXT\"}"),
                        RuntimeIntegrationTestSupport.node("end", WorkflowNodeType.END, "{}")),
                Arrays.asList(
                        RuntimeIntegrationTestSupport.edge("edge-1", "start", "first-llm"),
                        RuntimeIntegrationTestSupport.edge("edge-2", "first-llm", "second-llm"),
                        RuntimeIntegrationTestSupport.edge("edge-3", "second-llm", "end")));
    }
}
