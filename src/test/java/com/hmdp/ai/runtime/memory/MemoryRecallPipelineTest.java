package com.hmdp.ai.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.memory.MemoryFact;
import com.hmdp.ai.domain.memory.MemoryFactStatus;
import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryRecallPipelineTest {
  private final MemoryRepository repository = mock(MemoryRepository.class);
  private final EpisodicMemoryRetriever episodes = mock(EpisodicMemoryRetriever.class);
  private final ConversationMemoryRetriever conversation = mock(ConversationMemoryRetriever.class);
  private final UserProfileRetriever profile = mock(UserProfileRetriever.class);

  @Test
  void disabledLongTermMemoryStopsAllRecallImmediately() {
    when(repository.longTermMemoryEnabled("tenant", "workspace", "user")).thenReturn(false);
    MemoryRecallResult result = pipeline().recall(context("quiet restaurant"));

    assertThat(result.getFacts()).isEmpty();
    assertThat(result.getEpisodes()).isEmpty();
    assertThat(result.getWarnings()).containsExactly("LONG_TERM_MEMORY_DISABLED");
    verify(repository, never()).findFacts("tenant", "workspace", "user", 0, 100);
    verify(episodes, never()).retrieve(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void recallsOnlyConfirmedNonSensitiveFactsAndPreservesProvenance() {
    when(repository.longTermMemoryEnabled("tenant", "workspace", "user")).thenReturn(true);
    when(repository.findFacts("tenant", "workspace", "user", 0, 100))
        .thenReturn(
            Arrays.asList(
                fact("candidate", "quiet restaurant", false, "NORMAL", MemoryFactStatus.CANDIDATE),
                fact("sensitive", "phone 13800138000", true, "HIGH", MemoryFactStatus.CONFIRMED),
                fact("confirmed", "quiet restaurant", true, "NORMAL", MemoryFactStatus.CONFIRMED),
                fact("corrected", "vegetarian restaurant", true, "NORMAL", MemoryFactStatus.CORRECTED)));
    when(episodes.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyList());
    when(conversation.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn("USER: quiet restaurant");
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("email", "person@example.com");
    nested.put("cuisine", "vegetarian");
    Map<String, Object> profileValue = new LinkedHashMap<>();
    profileValue.put("preferences", nested);
    profileValue.put("apiToken", "must-not-leak");
    when(profile.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(profileValue);

    MemoryRecallResult result = pipeline().recall(context("quiet restaurant"));

    assertThat(result.getFacts()).extracting(value -> value.get("factValue"))
        .containsExactly("quiet restaurant", "vegetarian restaurant");
    assertThat(result.getFacts()).allSatisfy(value -> assertThat(value).doesNotContainKey("sensitivityLevel"));
    assertThat(result.getProfile().toString())
        .contains("vegetarian")
        .doesNotContain("person@example.com", "must-not-leak");
    assertThat(result.getProvenance().get("factSourceRunIds"))
        .isEqualTo(Arrays.asList("run-confirmed", "run-corrected"));
    assertThat(result.getProvenance().get("factSourceMessageIds"))
        .isEqualTo(Arrays.asList("message-confirmed", "message-corrected"));
  }

  @Test
  void compressorSkipsOversizedEntryAndStillUsesRemainingBudget() {
    MemoryContextCompressor compressor = new MemoryContextCompressor(new ObjectMapper());
    Map<String, Object> oversized = Collections.singletonMap("value", repeat("x", 100));
    Map<String, Object> compact = Collections.singletonMap("value", "ok");

    assertThat(compressor.compress(Arrays.asList(oversized, compact), 8)).containsExactly(compact);
  }

  private MemoryRecallPipeline pipeline() {
    return new MemoryRecallPipeline(
        repository,
        new FactMemoryRetriever(repository),
        episodes,
        conversation,
        profile,
        new MemorySensitivityFilter(),
        new MemoryRelevanceRanker(),
        new MemoryContextCompressor(new ObjectMapper()));
  }

  private ExecutionContext context(String text) {
    return new ExecutionContext(
        "tenant",
        "workspace",
        "user",
        "session",
        "conversation",
        "current-run",
        "agent",
        1,
        "zh-CN",
        "Asia/Shanghai",
        Collections.emptyList(),
        Collections.emptyList(),
        new AuthorizationContext(EnumSet.noneOf(AiPermission.class)),
        ExecutionBudget.defaults(),
        Instant.now().plusSeconds(30),
        Collections.singletonMap("text", text),
        "trace");
  }

  private MemoryFact fact(
      String id,
      String value,
      boolean confirmed,
      String sensitivity,
      MemoryFactStatus status) {
    Instant now = Instant.now();
    return new MemoryFact(
        id,
        "tenant",
        "workspace",
        "user",
        "PREFERENCE",
        value,
        "message-" + id,
        "run-" + id,
        0.9,
        confirmed,
        sensitivity,
        now.plusSeconds(3600),
        status,
        now,
        now);
  }

  private String repeat(String value, int count) {
    return String.join("", Collections.nCopies(count, value));
  }
}
