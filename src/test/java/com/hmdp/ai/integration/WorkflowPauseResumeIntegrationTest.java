package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.node.TextNode;
import com.hmdp.ai.application.agent.AgentRunResumeService;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.application.dto.agent.ResumeAgentRunRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.node.HumanNodeExecutor;
import com.hmdp.ai.runtime.workflow.WorkflowPausedException;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.exception.AiPlatformException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class WorkflowPauseResumeIntegrationTest {
    @Test
    void persistsHumanFeedbackContinuationValidatesTokenAndDoesNotRerunCompletedNodes() throws Exception {
        PromptVersion prompt = RuntimeIntegrationTestSupport.prompt("resume-prompt", 1,
                "Continue after human clarification.", "Use clarification: {{clarification}}",
                "{\"type\":\"object\",\"required\":[\"clarification\"]}");
        ModelProfileVersion model = RuntimeIntegrationTestSupport.modelProfileVersion();
        PublishedAgentDefinition agent = RuntimeIntegrationTestSupport.agent(
                "research-assistant", prompt, model, "pause-workflow");
        RuntimeIntegrationTestSupport.FixedPromptRepository prompts =
                new RuntimeIntegrationTestSupport.FixedPromptRepository(prompt);
        AtomicInteger modelCalls = new AtomicInteger();

        try (RuntimeIntegrationTestSupport.WorkflowHarness harness =
                     RuntimeIntegrationTestSupport.workflowHarness(prompts, model, invocation -> {
                         modelCalls.incrementAndGet();
                         return new ModelInvocationResult("resumed answer", null, 8, 3, false,
                                 4, new BigDecimal("0.001"), "provider-resume");
                     }, new HumanNodeExecutor(RuntimeIntegrationTestSupport.MAPPER))) {
            WorkflowDefinition workflow = workflow();
            AgentInputRequest input = new AgentInputRequest();
            input.setText("Need a scoped recommendation");
            ExecutionContext execution = execution(agent);
            AgentRunRecord queued = queuedRun(agent, input);
            harness.runs.create(queued);

            WorkflowPausedException paused = org.junit.jupiter.api.Assertions.assertThrows(
                    WorkflowPausedException.class,
                    () -> harness.runtime.execute(workflow, agent, execution, input));

            assertThat(paused.getRunStatus()).isEqualTo(RunStatus.WAITING_FOR_USER);
            assertThat(paused.getResumeToken()).isNotBlank();
            WorkflowState waiting = harness.states.find(RuntimeIntegrationTestSupport.TENANT,
                    RuntimeIntegrationTestSupport.WORKSPACE, execution.getRunId()).orElseThrow(AssertionError::new);
            assertThat(waiting.getStatus()).isEqualTo(WorkflowStateStatus.WAITING_FOR_USER);
            assertThat(waiting.getCurrentNodeCodes()).containsExactly("answer");
            assertThat(waiting.getCompletedNodeKeys()).contains("start", "human");
            assertThat(modelCalls).hasValue(0);

            RecordingAgentRuntime enqueue = new RecordingAgentRuntime();
            AgentRunResumeService resumes = new AgentRunResumeService(harness.runs, enqueue,
                    new ContentHashService(RuntimeIntegrationTestSupport.MAPPER),
                    RuntimeIntegrationTestSupport.MAPPER, harness.states);
            ResumeAgentRunRequest wrong = request("wrong-token", "nearby and quiet");
            AgentRunRecord waitingRun = harness.runs.find(RuntimeIntegrationTestSupport.TENANT,
                    RuntimeIntegrationTestSupport.WORKSPACE, execution.getRunId()).orElseThrow(AssertionError::new);

            assertThatThrownBy(() -> resumes.resume(security(), waitingRun, wrong))
                    .isInstanceOf(AiPlatformException.class)
                    .hasMessageContaining("resume token");
            assertThat(enqueue.calls).hasValue(0);

            AgentRunCreatedResponse response = resumes.resume(security(), waitingRun,
                    request(paused.getResumeToken(), "nearby and quiet"));

            assertThat(response.getStatus()).isEqualTo(RunStatus.QUEUED);
            assertThat(response.getAgentDefinitionId()).isEqualTo(agent.getAgent().getId());
            assertThat(response.getAgentCode()).isEqualTo("research-assistant");
            assertThat(enqueue.calls).hasValue(1);
            AgentRunOutput output = harness.runtime.execute(workflow, agent, execution, input);

            assertThat(output.getAnswer()).isEqualTo("resumed answer");
            assertThat(modelCalls).hasValue(1);
            Map<String, Long> starts = harness.nodeRuns.starts.stream()
                    .collect(Collectors.groupingBy(value -> value.nodeId, Collectors.counting()));
            assertThat(starts).containsEntry("start", 1L).containsEntry("human", 1L)
                    .containsEntry("answer", 1L).containsEntry("end", 1L);
            assertThat(harness.states.find(RuntimeIntegrationTestSupport.TENANT,
                    RuntimeIntegrationTestSupport.WORKSPACE, execution.getRunId())
                    .orElseThrow(AssertionError::new).getStatus()).isEqualTo(WorkflowStateStatus.COMPLETED);
        }
    }

    private WorkflowDefinition workflow() {
        return RuntimeIntegrationTestSupport.workflow("pause-workflow", Arrays.asList(
                        RuntimeIntegrationTestSupport.node("start", WorkflowNodeType.START, "{}"),
                        RuntimeIntegrationTestSupport.node("human", WorkflowNodeType.HUMAN_FEEDBACK,
                                "{\"question\":\"Which preference matters most?\",\"waitTtlSeconds\":300}"),
                        RuntimeIntegrationTestSupport.node("answer", WorkflowNodeType.LLM,
                                "{\"useAgentDefaultPrompt\":true,\"outputVariable\":\"agentOutput\"}"),
                        RuntimeIntegrationTestSupport.node("end", WorkflowNodeType.END, "{}")),
                Arrays.asList(
                        RuntimeIntegrationTestSupport.edge("edge-1", "start", "human"),
                        RuntimeIntegrationTestSupport.edge("edge-2", "human", "answer"),
                        RuntimeIntegrationTestSupport.edge("edge-3", "answer", "end")));
    }

    private ExecutionContext execution(PublishedAgentDefinition agent) {
        return new ExecutionContext(RuntimeIntegrationTestSupport.TENANT,
                RuntimeIntegrationTestSupport.WORKSPACE, "user", "session", "conversation", "run-pause",
                agent.getAgent().getId(), agent.getVersion().getVersion(), "en-US", "UTC",
                Collections.emptyList(), Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                Instant.now().plusSeconds(60), Collections.emptyMap(), "trace-pause");
    }

    private AgentRunRecord queuedRun(PublishedAgentDefinition agent, AgentInputRequest input) throws Exception {
        Instant now = Instant.now();
        ExecutionBudgetFactory budgets = new ExecutionBudgetFactory(RuntimeIntegrationTestSupport.MAPPER);
        return new AgentRunRecord("run-pause", RuntimeIntegrationTestSupport.TENANT,
                RuntimeIntegrationTestSupport.WORKSPACE, "user", "session", "conversation",
                agent.getAgent().getId(), agent.getVersion().getVersion(), RunStatus.QUEUED, "BLOCKING",
                RuntimeIntegrationTestSupport.MAPPER.writeValueAsString(input), null, "{}",
                RuntimeIntegrationTestSupport.MAPPER.writeValueAsString(agent.getVersionSnapshot()),
                budgets.snapshotJson(ExecutionBudget.defaults()), "{\"permissions\":[\"AGENT_RUN\"]}",
                "trace-pause", null, 1, null, null, now, null, null, now.plusSeconds(120), now);
    }

    private ResumeAgentRunRequest request(String token, String clarification) {
        ResumeAgentRunRequest request = new ResumeAgentRunRequest();
        request.setResumeToken(token);
        Map<String, com.fasterxml.jackson.databind.JsonNode> variables = new LinkedHashMap<>();
        variables.put("clarification", TextNode.valueOf(clarification));
        request.setVariables(variables);
        return request;
    }

    private AiSecurityContext security() {
        return new AiSecurityContext("user", new TenantContext(RuntimeIntegrationTestSupport.TENANT),
                new WorkspaceContext(RuntimeIntegrationTestSupport.WORKSPACE),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), false);
    }

    private static final class RecordingAgentRuntime implements AgentRuntime {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void enqueue(String tenantId, String workspaceId, String runId) {
            calls.incrementAndGet();
        }

        @Override
        public void recover() {
        }
    }
}
