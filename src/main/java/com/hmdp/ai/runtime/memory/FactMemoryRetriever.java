package com.hmdp.ai.runtime.memory;

import com.hmdp.ai.domain.memory.MemoryFact;
import com.hmdp.ai.domain.memory.MemoryFactStatus;
import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.run.ExecutionContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FactMemoryRetriever {
  private final MemoryRepository repository;

  public FactMemoryRetriever(MemoryRepository repository) {
    this.repository = repository;
  }

  public List<Map<String, Object>> retrieve(ExecutionContext context) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (MemoryFact fact :
        repository.findFacts(
            context.getTenantId(), context.getWorkspaceId(), context.getUserId(), 0, 100)) {
      if (!fact.isConfirmedByUser() || !recallable(fact.getStatus())) continue;
      if (fact.getExpiresAt() != null && !fact.getExpiresAt().isAfter(Instant.now())) continue;
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("factType", fact.getFactType());
      value.put("factValue", fact.getFactValue());
      value.put("sourceMessageId", fact.getSourceMessageId());
      value.put("sourceRunId", fact.getSourceRunId());
      value.put("confidence", fact.getConfidence());
      value.put("sensitivityLevel", fact.getSensitivityLevel());
      result.add(value);
    }
    return result;
  }

  private boolean recallable(MemoryFactStatus status) {
    return status == MemoryFactStatus.CONFIRMED || status == MemoryFactStatus.CORRECTED;
  }
}
