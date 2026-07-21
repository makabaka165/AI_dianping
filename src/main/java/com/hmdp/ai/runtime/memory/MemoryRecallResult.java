package com.hmdp.ai.runtime.memory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MemoryRecallResult {
  private final List<Map<String, Object>> facts;
  private final List<Map<String, Object>> episodes;
  private final String conversationSummary;
  private final Map<String, Object> profile;
  private final List<String> warnings;
  private final Map<String, Object> provenance;

  public MemoryRecallResult(
      List<Map<String, Object>> facts,
      List<Map<String, Object>> episodes,
      String conversationSummary,
      Map<String, Object> profile,
      List<String> warnings,
      Map<String, Object> provenance) {
    this.facts = immutable(facts);
    this.episodes = immutable(episodes);
    this.conversationSummary = conversationSummary == null ? "" : conversationSummary;
    this.profile = profile == null ? Collections.emptyMap() : Collections.unmodifiableMap(profile);
    this.warnings =
        warnings == null ? Collections.emptyList() : Collections.unmodifiableList(warnings);
    this.provenance =
        provenance == null ? Collections.emptyMap() : Collections.unmodifiableMap(provenance);
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
  }

  public List<Map<String, Object>> getFacts() {
    return facts;
  }

  public List<Map<String, Object>> getEpisodes() {
    return episodes;
  }

  public String getConversationSummary() {
    return conversationSummary;
  }

  public Map<String, Object> getProfile() {
    return profile;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public Map<String, Object> getProvenance() {
    return provenance;
  }
}
