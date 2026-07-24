package com.hmdp.ai.infrastructure.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedisKnowledgeIndexNamingTest {
  @Test
  void v2NamesAreStableAndDoNotNormalizeDifferentVersionsToOneName() {
    String dashed = RedisKnowledgeIndexNaming.indexName("a-b");
    String underscored = RedisKnowledgeIndexNaming.indexName("a_b");

    assertThat(dashed).isEqualTo(RedisKnowledgeIndexNaming.indexName("a-b"));
    assertThat(dashed).isNotEqualTo(underscored);
    assertThat(dashed).matches("ai_kb_v2~[0-9a-f]{32}");
  }

  @Test
  void v2HashUsesTheDocumentedUtf8Sha256Prefix() {
    assertThat(RedisKnowledgeIndexNaming.indexName("shop-enterprise-v1"))
        .isEqualTo("ai_kb_v2~a27653f4c57c44663cd85955499113c7");
  }

  @Test
  void legacyNameAndCandidatesRemainAvailableForRollback() {
    String version = "a-b";

    assertThat(RedisKnowledgeIndexNaming.legacyIndexName(version)).isEqualTo("ai_kb_a_b");
    assertThat(RedisKnowledgeIndexNaming.v1IndexName(version))
        .isEqualTo(RedisKnowledgeIndexNaming.legacyIndexName(version));
    List<String> candidates = RedisKnowledgeIndexNaming.candidateIndexNames(version);
    assertThat(candidates)
        .containsExactly(
            RedisKnowledgeIndexNaming.indexName(version),
            RedisKnowledgeIndexNaming.legacyIndexName(version));
  }

  @Test
  void candidateListDoesNotDuplicateANameWhenFormatsCoincide() {
    List<String> candidates = RedisKnowledgeIndexNaming.candidateIndexNames("v2");

    assertThat(candidates).hasSize(2).doesNotHaveDuplicates();
  }

  @Test
  void v1NamespaceCannotProduceTheV2Marker() {
    String v2Name = RedisKnowledgeIndexNaming.indexName("a-b");
    String legacyLookalike = RedisKnowledgeIndexNaming.legacyIndexName(v2Name.substring(6));

    assertThat(legacyLookalike).isNotEqualTo(v2Name);
  }
}
