package com.hmdp.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.AgentRunAccessPolicy;
import com.hmdp.ai.application.agent.AgentRunApplicationService;
import com.hmdp.ai.application.agent.AgentRunCancellationService;
import com.hmdp.ai.application.agent.AgentRunRequestValidator;
import com.hmdp.ai.application.agent.AgentRunResumeService;
import com.hmdp.ai.application.agent.AgentRunRetryService;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.application.agent.event.SseRunEventHub;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.agent.AgentResponseMode;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.application.dto.agent.AgentRunRequest;
import com.hmdp.ai.application.dto.agent.ResumeAgentRunRequest;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiAgentIdentityContractTest {
  private static final String TENANT = "identity-tenant";
  private static final String WORKSPACE = "identity-workspace";
  private static final String USER = "identity-user";
  private static final String AGENT_DEFINITION_ID = "agent-definition-42";
  private static final String AGENT_CODE = "identity-agent";
  private static final int AGENT_VERSION = 7;

  private final ObjectMapper mapper = new ObjectMapper();
  private final ExecutionBudgetFactory budgets = new ExecutionBudgetFactory(mapper);

  @Test
  void createCancelRetryAndResumeExposeTheSameAgentIdentityContract() throws Exception {
    AiSecurityContext security = security();
    PublishedAgentDefinition definition = definition();

    AgentRunCreatedResponse created = create(security, definition);
    AgentRunCreatedResponse cancelled = cancel(security, run("cancel-run", RunStatus.QUEUED));
    AgentRunCreatedResponse retried = retry(security, run("failed-run", RunStatus.FAILED));
    AgentRunCreatedResponse resumed = resume(security, run("waiting-run", RunStatus.WAITING_FOR_USER));

    assertIdentity(created, RunStatus.QUEUED);
    assertIdentity(cancelled, RunStatus.CANCELLED);
    assertIdentity(retried, RunStatus.QUEUED);
    assertIdentity(resumed, RunStatus.QUEUED);
  }

  private AgentRunCreatedResponse create(
      AiSecurityContext security, PublishedAgentDefinition definition) {
    RunRepository repository = mock(RunRepository.class);
    AgentDefinitionLoader loader = mock(AgentDefinitionLoader.class);
    AgentRuntime runtime = mock(AgentRuntime.class);
    AiAccessGuard access = mock(AiAccessGuard.class);
    AiIdGenerator ids = mock(AiIdGenerator.class);
    when(access.require(AiPermission.AGENT_RUN)).thenReturn(security);
    when(loader.load(TENANT, WORKSPACE, AGENT_CODE, AGENT_VERSION)).thenReturn(definition);
    when(ids.nextId()).thenReturn("created-run", "created-trace");
    when(repository.create(any(AgentRunRecord.class))).thenAnswer(value -> value.getArgument(0));
    AgentRunApplicationService service =
        new AgentRunApplicationService(
            repository,
            loader,
            mock(AgentRunRequestValidator.class),
            new AgentRunAccessPolicy(),
            mock(AgentRunCancellationService.class),
            mock(AgentRunRetryService.class),
            mock(AgentRunResumeService.class),
            runtime,
            mock(SseRunEventHub.class),
            access,
            ids,
            new ContentHashService(mapper),
            budgets,
            mapper);

    AgentRunCreatedResponse response = service.create(createRequest());

    verify(runtime).enqueue(TENANT, WORKSPACE, "created-run");
    return response;
  }

  private AgentRunCreatedResponse cancel(AiSecurityContext security, AgentRunRecord run) {
    RunRepository repository = mock(RunRepository.class);
    when(repository.cancel(TENANT, WORKSPACE, run.getId(), USER)).thenReturn(true);
    AgentRunCancellationService service =
        new AgentRunCancellationService(
            repository, mock(RunEventPublisher.class), mapper, null);
    return service.cancel(security, run);
  }

  private AgentRunCreatedResponse retry(AiSecurityContext security, AgentRunRecord source) {
    RunRepository repository = mock(RunRepository.class);
    AgentRuntime runtime = mock(AgentRuntime.class);
    AiIdGenerator ids = mock(AiIdGenerator.class);
    when(ids.nextId()).thenReturn("retry-run", "retry-trace");
    when(repository.create(any(AgentRunRecord.class))).thenAnswer(value -> value.getArgument(0));
    AgentRunRetryService service =
        new AgentRunRetryService(repository, runtime, ids, budgets, mapper);

    AgentRunCreatedResponse response = service.retry(security, source);

    verify(runtime).enqueue(TENANT, WORKSPACE, "retry-run");
    return response;
  }

  private AgentRunCreatedResponse resume(AiSecurityContext security, AgentRunRecord run) {
    RunRepository repository = mock(RunRepository.class);
    AgentRuntime runtime = mock(AgentRuntime.class);
    WorkflowStateRepository workflowStates = mock(WorkflowStateRepository.class);
    ContentHashService hashes = new ContentHashService(mapper);
    ResumeAgentRunRequest request = new ResumeAgentRunRequest();
    request.setResumeToken("resume-token");
    request.setVariables(Collections.emptyMap());
    String tokenHash = hashes.sha256(request.getResumeToken());
    when(workflowStates.resume(TENANT, WORKSPACE, run.getId(), tokenHash, Collections.emptyMap(), USER))
        .thenReturn(true);
    when(repository.resumeWaiting(
            TENANT,
            WORKSPACE,
            run.getId(),
            tokenHash,
            "{}",
            USER))
        .thenReturn(true);
    AgentRunResumeService service =
        new AgentRunResumeService(repository, runtime, hashes, mapper, workflowStates);

    AgentRunCreatedResponse response = service.resume(security, run, request);

    verify(runtime).enqueue(TENANT, WORKSPACE, run.getId());
    return response;
  }

  private void assertIdentity(AgentRunCreatedResponse response, RunStatus expectedStatus) {
    JsonNode json = mapper.valueToTree(response);
    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder(
            "runId", "status", "agentDefinitionId", "agentCode", "agentVersion");
    assertThat(json.has("agentId")).isFalse();
    assertThat(json.path("status").asText()).isEqualTo(expectedStatus.name());
    assertThat(json.path("agentDefinitionId").asText()).isEqualTo(AGENT_DEFINITION_ID);
    assertThat(json.path("agentCode").asText()).isEqualTo(AGENT_CODE);
    assertThat(json.path("agentVersion").asInt()).isEqualTo(AGENT_VERSION);
  }

  private List<String> fieldNames(JsonNode json) {
    List<String> result = new ArrayList<>();
    Iterator<String> names = json.fieldNames();
    names.forEachRemaining(result::add);
    return result;
  }

  private AgentRunRequest createRequest() {
    AgentInputRequest input = new AgentInputRequest();
    input.setText("verify stable identity");
    AgentRunRequest request = new AgentRunRequest();
    request.setAgentId(AGENT_CODE);
    request.setAgentVersion(AGENT_VERSION);
    request.setSessionId("identity-session");
    request.setInput(input);
    request.setResponseMode(AgentResponseMode.BLOCKING);
    return request;
  }

  private AiSecurityContext security() {
    return new AiSecurityContext(
        USER,
        new TenantContext(TENANT),
        new WorkspaceContext(WORKSPACE),
        new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)),
        false);
  }

  private AgentRunRecord run(String id, RunStatus status) {
    Instant now = Instant.now();
    return new AgentRunRecord(
        id,
        TENANT,
        WORKSPACE,
        USER,
        "identity-session",
        "identity-conversation",
        AGENT_DEFINITION_ID,
        AGENT_VERSION,
        status,
        "BLOCKING",
        "{\"text\":\"identity\"}",
        null,
        "{}",
        snapshotJson(),
        budgets.snapshotJson(ExecutionBudget.defaults()),
        "{\"permissions\":[\"AGENT_RUN\"]}",
        "identity-trace",
        null,
        1,
        status == RunStatus.FAILED ? "TEST_FAILURE" : null,
        status == RunStatus.FAILED ? "retryable failure" : null,
        now.minusSeconds(1),
        null,
        status == RunStatus.FAILED ? now : null,
        now.plusSeconds(60),
        now.minusSeconds(1));
  }

  private PublishedAgentDefinition definition() {
    AgentDefinition agent =
        new AgentDefinition(
            AGENT_DEFINITION_ID,
            TENANT,
            WORKSPACE,
            AGENT_CODE,
            "Identity agent",
            "Identity contract fixture",
            AGENT_VERSION,
            "ACTIVE",
            null,
            null);
    AgentVersion version =
        new AgentVersion(
            "identity-agent-version",
            TENANT,
            WORKSPACE,
            AGENT_DEFINITION_ID,
            AGENT_VERSION,
            "Identity agent",
            "Identity contract fixture",
            "identity-model",
            "identity-model-version",
            "identity-prompt-version",
            "identity-workflow-version",
            "{}",
            "{\"type\":\"object\"}",
            "{\"type\":\"object\"}",
            "{\"maxWorkflowNodes\":64,\"maxLoopIterations\":5,\"maxParallelism\":4,"
                + "\"maxModelCalls\":8,\"maxToolCalls\":16,\"maxRunDurationSeconds\":120}",
            "{}",
            VersionStatus.PUBLISHED,
            "identity-content-hash",
            "identity contract",
            Instant.now(),
            USER,
            Instant.now());
    return new PublishedAgentDefinition(
        agent,
        version,
        null,
        null,
        null,
        "identity-workflow",
        3,
        "PUBLISHED",
        Collections.emptyList(),
        Collections.emptyList(),
        snapshot());
  }

  private String snapshotJson() {
    try {
      return mapper.writeValueAsString(snapshot());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private VersionSnapshot snapshot() {
    return new VersionSnapshot(
        AGENT_DEFINITION_ID,
        AGENT_CODE,
        AGENT_VERSION,
        "identity-prompt",
        2,
        "identity-workflow",
        3,
        "identity-model",
        "identity-model-version",
        4,
        "identity-model-hash",
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptyMap());
  }
}
