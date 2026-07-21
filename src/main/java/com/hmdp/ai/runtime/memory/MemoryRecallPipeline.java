package com.hmdp.ai.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.run.ExecutionContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MemoryRecallPipeline {
  private final MemoryRepository repository;
  private final FactMemoryRetriever facts;
  private final EpisodicMemoryRetriever episodes;
  private final ConversationMemoryRetriever conversation;
  private final UserProfileRetriever profile;
  private final MemorySensitivityFilter sensitivity;
  private final MemoryRelevanceRanker relevance;
  private final MemoryContextCompressor compressor;

  @Autowired
  public MemoryRecallPipeline(
      MemoryRepository repository,
      FactMemoryRetriever facts,
      EpisodicMemoryRetriever episodes,
      ConversationMemoryRetriever conversation,
      UserProfileRetriever profile,
      MemorySensitivityFilter sensitivity,
      MemoryRelevanceRanker relevance,
      MemoryContextCompressor compressor) {
    this.repository = repository;
    this.facts = facts;
    this.episodes = episodes;
    this.conversation = conversation;
    this.profile = profile;
    this.sensitivity = sensitivity;
    this.relevance = relevance;
    this.compressor = compressor;
  }

  /** Compatibility constructor for focused tests that do not start Spring. */
  public MemoryRecallPipeline(MemoryRepository repository, JdbcTemplate jdbc, ObjectMapper mapper) {
    this(
        repository,
        new FactMemoryRetriever(repository),
        new EpisodicMemoryRetriever(jdbc),
        new ConversationMemoryRetriever(repository),
        new UserProfileRetriever(jdbc, mapper),
        new MemorySensitivityFilter(),
        new MemoryRelevanceRanker(),
        new MemoryContextCompressor(mapper));
  }

  public MemoryRecallResult recall(ExecutionContext context) {
    if (!repository.longTermMemoryEnabled(
        context.getTenantId(), context.getWorkspaceId(), context.getUserId())) {
      return new MemoryRecallResult(
          Collections.emptyList(),
          Collections.emptyList(),
          "",
          Collections.emptyMap(),
          Collections.singletonList("LONG_TERM_MEMORY_DISABLED"),
          provenance(context, Collections.emptyList(), Collections.emptyList()));
    }

    List<String> warnings = new ArrayList<>();
    String query = String.valueOf(context.getVariables().getOrDefault("text", ""));
    int tokenBudget =
        (int) Math.max(256, Math.min(4000, context.getExecutionBudget().getMaxInputTokens() / 8));
    List<Map<String, Object>> factValues = safeFacts(context, warnings);
    List<Map<String, Object>> episodeValues = safeEpisodes(context, warnings);
    factValues = relevance.rank(sensitivity.filter(factValues), query, 50);
    episodeValues = relevance.rank(sensitivity.filter(episodeValues), query, 25);
    factValues = compressor.compress(factValues, tokenBudget / 3);
    episodeValues = compressor.compress(episodeValues, tokenBudget / 3);
    String summary = safeConversation(context, warnings);
    summary = compressor.compressText(summary, tokenBudget / 5);
    Map<String, Object> userProfile = safeProfile(context, warnings);
    userProfile = sensitivity.filterProfile(userProfile);
    Map<String, Object> provenance = provenance(context, factValues, episodeValues);
    return new MemoryRecallResult(
        factValues, episodeValues, summary, userProfile, warnings, provenance);
  }

  private List<Map<String, Object>> safeFacts(ExecutionContext context, List<String> warnings) {
    try {
      return facts.retrieve(context);
    } catch (Exception error) {
      warnings.add("FACT_RECALL_UNAVAILABLE");
      return Collections.emptyList();
    }
  }

  private List<Map<String, Object>> safeEpisodes(ExecutionContext context, List<String> warnings) {
    try {
      return episodes.retrieve(context);
    } catch (Exception error) {
      warnings.add("EPISODIC_RECALL_UNAVAILABLE");
      return Collections.emptyList();
    }
  }

  private String safeConversation(ExecutionContext context, List<String> warnings) {
    try {
      return conversation.retrieve(context);
    } catch (Exception error) {
      warnings.add("CONVERSATION_RECALL_UNAVAILABLE");
      return "";
    }
  }

  private Map<String, Object> safeProfile(ExecutionContext context, List<String> warnings) {
    try {
      return profile.retrieve(context);
    } catch (Exception error) {
      warnings.add("PROFILE_RECALL_UNAVAILABLE");
      return Collections.emptyMap();
    }
  }

  private Map<String, Object> provenance(
      ExecutionContext context,
      List<Map<String, Object>> facts,
      List<Map<String, Object>> episodes) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceRunId", context.getRunId());
    result.put("conversationId", context.getConversationId());
    result.put("factSourceRunIds", sourceIds(facts, "sourceRunId"));
    result.put("factSourceMessageIds", sourceIds(facts, "sourceMessageId"));
    result.put("episodeSourceRunIds", sourceIds(episodes, "sourceRunId"));
    return result;
  }

  private List<String> sourceIds(List<Map<String, Object>> values, String key) {
    List<String> result = new ArrayList<>();
    for (Map<String, Object> value : values) {
      Object source = value.get(key);
      if (source != null && !result.contains(String.valueOf(source)))
        result.add(String.valueOf(source));
    }
    return result;
  }
}
