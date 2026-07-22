package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.evaluation.EvaluationExecutionOptions;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.KnowledgeRetrievalRequest;
import com.hmdp.ai.domain.knowledge.KnowledgeRetriever;
import com.hmdp.ai.domain.knowledge.RetrievalRecord;
import com.hmdp.ai.domain.knowledge.RetrievalRecordPort;
import com.hmdp.ai.domain.knowledge.RetrievalTrace;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.observability.RunInspectionPort;
import com.hmdp.ai.domain.observability.RunUsageSummary;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.runtime.model.GenericModelGateway;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.runtime.evaluation.PromptEvaluationRunner;
import com.hmdp.ai.runtime.evaluation.ToolEvaluationRunner;
import com.hmdp.ai.runtime.prompt.PromptRenderer;
import com.hmdp.ai.runtime.prompt.RenderedPrompt;
import com.hmdp.ai.runtime.tool.ToolExecutionPipeline;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationTargetRunnersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolverExposesOnlyImplementedTargetTypes() {
        EvaluationTargetResolver resolver = new EvaluationTargetResolver(Arrays.asList(
                runner("AGENT"), runner("WORKFLOW"), runner("PROMPT"), runner("RAG"), runner("TOOL")));

        assertThat(resolver.resolve("agent").targetType()).isEqualTo("AGENT");
        assertThat(resolver.resolve("PROMPT").targetType()).isEqualTo("PROMPT");
        assertThat(resolver.resolve("rag").targetType()).isEqualTo("RAG");
        assertThatThrownBy(() -> resolver.resolve("SECURITY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("EVALUATION_TARGET_UNSUPPORTED");
    }

    @Test
    void promptRunnerUsesVersionedPromptAndGenericModelGateway() throws Exception {
        RunRepository runRepository = runRepository();
        PromptRepository prompts = mock(PromptRepository.class);
        ModelProfileVersionRepository models = mock(ModelProfileVersionRepository.class);
        GenericModelGateway gateway = mock(GenericModelGateway.class);
        PromptRenderer renderer = mock(PromptRenderer.class);
        PromptVersion prompt = prompt();
        ModelProfileVersion model = model();
        when(prompts.findVersion("tenant", "workspace", "prompt", 2)).thenReturn(Optional.of(prompt));
        when(models.findById("tenant", "workspace", "model-version")).thenReturn(Optional.of(model));
        when(renderer.render(any(), any(), any())).thenReturn(new RenderedPrompt(
                "system", "question: hello", "rendered summary"));
        when(gateway.invoke(any())).thenReturn(new ModelInvocationResult("{\"answer\":\"ok\"}",
                mapper.readTree("{\"answer\":\"ok\"}"), 11, 4, false, 7,
                new BigDecimal("0.004"), "provider-call"));
        PromptEvaluationRunner runner = new PromptEvaluationRunner(prompts, models, gateway, renderer,
                support(runRepository), mapper);
        EvaluationExecutionOptions options = new EvaluationExecutionOptions();
        options.setModelProfileVersionId("model-version");
        EvaluationTargetRequest request = request("PROMPT", "prompt", 2,
                "{\"question\":\"hello\"}", options);

        EvaluationExecutionResult result = runner.execute(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRunId()).isNotBlank();
        assertThat(result.getActual().path("answer").asText()).isEqualTo("ok");
        assertThat(result.getModelCalls()).isEqualTo(1);
        assertThat(result.getInputTokens()).isEqualTo(11);
        ArgumentCaptor<ModelInvocation> invocation = ArgumentCaptor.forClass(ModelInvocation.class);
        verify(gateway).invoke(invocation.capture());
        assertThat(invocation.getValue().getModelProfileVersionId()).isEqualTo("model-version");
        assertThat(invocation.getValue().getContext().getInvocationContext().getNodeRunId())
                .endsWith(":evaluation-node");
        assertThat(invocation.getValue().getContext().getInvocationContext().getRunId())
                .isEqualTo(result.getRunId());
        verify(runRepository).complete("tenant", "workspace", result.getRunId(),
                mapper.writeValueAsString(result.getActual()));
    }

    @Test
    void ragRunnerRecordsInvocationTraceWithNodeRunId() {
        RunRepository runRepository = runRepository();
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        RetrievalRecordPort records = mock(RetrievalRecordPort.class);
        HybridRetrievalResult retrieval = new HybridRetrievalResult(Collections.emptyList(),
                "FALLBACK_RRF", Collections.singletonList("NO_MATCH"),
                new RetrievalTrace(4, "kb-index-v4", 3, 2, 1, 0, Collections.emptyList()));
        when(retriever.retrieve(any(KnowledgeRetrievalRequest.class))).thenReturn(retrieval);
        RagEvaluationRunner runner = new RagEvaluationRunner(retriever, records,
                support(runRepository), mapper);

        EvaluationExecutionResult result = runner.execute(request("RAG", "kb", 4,
                "{\"query\":\"refund policy\",\"topK\":5}", new EvaluationExecutionOptions()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getActual().path("rerankMode").asText()).isEqualTo("FALLBACK_RRF");
        assertThat(result.getActual().path("trace").path("indexVersion").asText())
                .isEqualTo("kb-index-v4");
        ArgumentCaptor<KnowledgeRetrievalRequest> request =
                ArgumentCaptor.forClass(KnowledgeRetrievalRequest.class);
        verify(retriever).retrieve(request.capture());
        assertThat(request.getValue().getInvocationContext().getNodeRunId())
                .endsWith(":evaluation-node");
        assertThat(request.getValue().getInvocationContext().getRunId()).isEqualTo(result.getRunId());
        ArgumentCaptor<RetrievalRecord> record = ArgumentCaptor.forClass(RetrievalRecord.class);
        verify(records).record(record.capture());
        assertThat(record.getValue().getContext().getNodeRunId())
                .isEqualTo(request.getValue().getInvocationContext().getNodeRunId());
    }

    @Test
    void toolRunnerUsesBoundAgentContextAndToolPipeline() {
        RunRepository runRepository = runRepository();
        AgentDefinitionLoader definitions = mock(AgentDefinitionLoader.class);
        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        PublishedAgentDefinition definition = agent();
        when(definitions.load("tenant", "workspace", "agent", 3)).thenReturn(definition);
        when(pipeline.execute(any())).thenReturn(ToolResult.success(
                mapper.createObjectNode().put("shopId", 7), 5));
        ToolEvaluationRunner runner = new ToolEvaluationRunner(definitions, pipeline,
                support(runRepository), new AiIdGenerator(), mapper);
        EvaluationExecutionOptions options = new EvaluationExecutionOptions();
        options.setAgentId("agent");
        options.setAgentVersion(3);

        EvaluationExecutionResult result = runner.execute(request("TOOL", "check-shop", 2,
                "{\"shopId\":7}", options));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolCalls()).isEqualTo(1);
        assertThat(result.getActual().path("selectedTool").asText()).isEqualTo("check-shop");
        ArgumentCaptor<ToolInvocation> invocation = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(pipeline).execute(invocation.capture());
        assertThat(invocation.getValue().getContext().getAgentId()).isEqualTo("agent-id");
        assertThat(invocation.getValue().getNodeRunId()).endsWith(":evaluation-node");
        assertThat(invocation.getValue().isApproved()).isFalse();
    }

    private EvaluationTargetRunner runner(String type) {
        return new EvaluationTargetRunner() {
            @Override public String targetType() { return type; }
            @Override public EvaluationExecutionResult execute(EvaluationTargetRequest request) {
                throw new AssertionError("not invoked");
            }
        };
    }

    private RunRepository runRepository() {
        RunRepository repository = mock(RunRepository.class);
        when(repository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.claimQueued(anyString(), anyString(), anyString())).thenReturn(true);
        return repository;
    }

    private EvaluationRunSupport support(RunRepository runs) {
        RunInspectionPort inspection = mock(RunInspectionPort.class);
        when(inspection.usage(anyString(), anyString(), anyString()))
                .thenReturn(new RunUsageSummary(0, 0, 0, 0, BigDecimal.ZERO));
        return new EvaluationRunSupport(runs, inspection, new ExecutionBudgetFactory(mapper),
                new AiIdGenerator(), mapper);
    }

    private EvaluationTargetRequest request(String type, String targetId, Integer version,
                                            String input, EvaluationExecutionOptions options) {
        EvaluationCase evaluationCase = new EvaluationCase("case", "tenant", "workspace", "dataset",
                "case", input, "{}", "{}", "ACTIVE");
        return new EvaluationTargetRequest(evaluationCase, type, targetId, version, options,
                "tenant", "workspace", "user", new AuthorizationContext(EnumSet.of(
                AiPermission.AGENT_RUN, AiPermission.KNOWLEDGE_READ, AiPermission.EVALUATION_RUN)));
    }

    private PromptVersion prompt() {
        Instant now = Instant.now();
        return new PromptVersion("prompt-version", "tenant", "workspace", "prompt", 2,
                "system", "answer {{question}}", null, null, null,
                "{\"type\":\"object\",\"required\":[\"question\"]}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "[]",
                VersionStatus.PUBLISHED, "prompt-hash", "published", now, "publisher", now);
    }

    private ModelProfileVersion model() {
        Instant now = Instant.now();
        return new ModelProfileVersion("model-version", "tenant", "workspace", "model", 5,
                "OPENAI_COMPATIBLE", "fake", "https://model.invalid/v1", "env:FAKE_MODEL_KEY",
                ModelType.CHAT, "{\"streaming\":false,\"jsonSchema\":true}", "{}", 8192, 1024,
                2000, "{\"maxAttempts\":1}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                "model-hash", "published", "PUBLISHED", now, "publisher", "creator",
                "publisher", now, now);
    }

    private PublishedAgentDefinition agent() {
        Instant now = Instant.now();
        AgentDefinition agent = new AgentDefinition("agent-id", "tenant", "workspace", "agent",
                "Agent", "evaluation agent", 3, "ACTIVE", now, now);
        AgentVersion version = new AgentVersion("agent-version", "tenant", "workspace", "agent-id", 3,
                "Agent", "evaluation agent", "model", "model-version", "prompt-version",
                "workflow-version", "{}", "{\"type\":\"object\"}",
                "{\"type\":\"object\"}", "{}", "{}", VersionStatus.PUBLISHED,
                "agent-hash", "published", now, "publisher", now);
        VersionSnapshot snapshot = new VersionSnapshot("agent-id", "agent", 3, "prompt", 1,
                "workflow", 1, "model", "model-version", 1, "model-hash",
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        return new PublishedAgentDefinition(agent, version, null, null, null,
                "workflow", 1, "PUBLISHED", Collections.emptyList(), Collections.emptyList(), snapshot);
    }
}
