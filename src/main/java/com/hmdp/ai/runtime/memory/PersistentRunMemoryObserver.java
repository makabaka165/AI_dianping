package com.hmdp.ai.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.memory.WorkingMemoryPort;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunCompletionObserver;
import com.hmdp.ai.guard.PiiDetectionService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PersistentRunMemoryObserver implements RunCompletionObserver {
  private static final Duration WORKING_MEMORY_TTL = Duration.ofHours(24);
  private static final Duration DECLARED_FACT_TTL = Duration.ofDays(365);
  private final MemoryRepository repository;
  private final WorkingMemoryPort workingMemory;
  private final PiiDetectionService pii;
  private final ObjectMapper mapper;

  public PersistentRunMemoryObserver(
      MemoryRepository repository,
      WorkingMemoryPort workingMemory,
      PiiDetectionService pii,
      ObjectMapper mapper) {
    this.repository = repository;
    this.workingMemory = workingMemory;
    this.pii = pii;
    this.mapper = mapper;
  }

  @Override
  public void onCompleted(AgentRunRecord run, String outputJson) {
    try {
      JsonNode input = mapper.readTree(run.getInputJson());
      String userText = input.path("text").asText("");
      AgentRunOutput output = mapper.readValue(outputJson, AgentRunOutput.class);
      String citations = mapper.writeValueAsString(output.getCitations());
      String usage = mapper.writeValueAsString(output.getUsage());
      String explicitFact = explicitFact(userText);
      repository.recordCompletedRun(
          run,
          userText,
          outputJson,
          output.getAnswer(),
          citations,
          usage,
          explicitFact,
          explicitFact == null ? null : Instant.now().plus(DECLARED_FACT_TTL));
      String snapshot =
          mapper.writeValueAsString(
              new WorkingSnapshot(userText, output.getAnswer(), output.getWarnings()));
      Instant expiresAt = Instant.now().plus(WORKING_MEMORY_TTL);
      repository.saveWorkingSnapshot(
          run.getTenantId(),
          run.getWorkspaceId(),
          run.getId(),
          run.getConversationId(),
          snapshot,
          expiresAt,
          run.getUserId());
      workingMemory.put(
          run.getTenantId(), run.getWorkspaceId(), run.getId(), snapshot, WORKING_MEMORY_TTL);
    } catch (Exception e) {
      throw new IllegalStateException("completed run memory cannot be persisted", e);
    }
  }

  private String explicitFact(String text) {
    if (text == null) return null;
    String value = text.trim();
    String fact = null;
    for (String prefix : new String[] {"请记住", "记住：", "记住:"})
      if (value.startsWith(prefix)) {
        fact = value.substring(prefix.length()).trim();
        break;
      }
    if (fact == null || fact.isEmpty() || fact.length() > 500 || !pii.detect(fact).isEmpty())
      return null;
    return fact;
  }

  private static final class WorkingSnapshot {
    private final String userText, answer;
    private final java.util.List<String> warnings;

    private WorkingSnapshot(String userText, String answer, java.util.List<String> warnings) {
      this.userText = userText;
      this.answer = answer;
      this.warnings = warnings;
    }

    public String getUserText() {
      return userText;
    }

    public String getAnswer() {
      return answer;
    }

    public java.util.List<String> getWarnings() {
      return warnings;
    }
  }
}
