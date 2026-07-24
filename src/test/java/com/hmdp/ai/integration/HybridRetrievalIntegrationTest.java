package com.hmdp.ai.integration;

import com.hmdp.ai.domain.knowledge.IndexHit;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import com.hmdp.ai.infrastructure.vector.RedisStackKnowledgeIndexAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class HybridRetrievalIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS_STACK = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.2.0-v10")).withExposedPorts(6379);

    @Test
    void redisStackEnforcesTenantWorkspaceAndUserAclDuringHybridRecall() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS_STACK.getHost(), REDIS_STACK.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        try {
            KnowledgeRepository repository = mock(KnowledgeRepository.class);
            when(repository.findDocumentReadPrincipals(eq("tenant"), eq("workspace"),
                    eq(Collections.singletonList("workspace-document"))))
                    .thenReturn(Collections.singletonMap(
                            "workspace-document", Collections.singletonList("workspace")));
            when(repository.findDocumentReadPrincipals(eq("tenant"), eq("workspace"),
                    eq(Collections.singletonList("private-document"))))
                    .thenReturn(Collections.singletonMap(
                            "private-document", Collections.singletonList("user:owner")));
            RedisStackKnowledgeIndexAdapter adapter = new RedisStackKnowledgeIndexAdapter(connectionFactory, repository);
            adapter.ensureIndex("integration-v1", 3);
            adapter.index(Collections.singletonList(chunk(
                    "workspace-chunk", "kb", "workspace-document",
                    "excellent service attitude", new float[]{1, 0, 0})));
            adapter.index(Collections.singletonList(chunk(
                    "private-chunk", "private-kb", "private-document",
                    "confidential service notes", new float[]{0, 1, 0})));

            KnowledgeSearchScope workspaceScope = new KnowledgeSearchScope(
                    "tenant", "workspace", "kb", "integration-v1", "user");
            List<IndexHit> vector = adapter.vectorSearch(workspaceScope, new float[]{1, 0, 0}, 5);
            List<IndexHit> lexical = adapter.lexicalSearch(workspaceScope, "service attitude", 5);
            assertThat(vector).extracting(IndexHit::getChunkId).contains("workspace-chunk");
            assertThat(lexical).extracting(IndexHit::getChunkId).contains("workspace-chunk");

            KnowledgeSearchScope ownerScope = new KnowledgeSearchScope(
                    "tenant", "workspace", "private-kb", "integration-v1", "owner");
            assertThat(adapter.lexicalSearch(ownerScope, "confidential", 5))
                    .extracting(IndexHit::getChunkId)
                    .contains("private-chunk");

            KnowledgeSearchScope otherUserScope = new KnowledgeSearchScope(
                    "tenant", "workspace", "private-kb", "integration-v1", "intruder");
            assertThat(adapter.vectorSearch(otherUserScope, new float[]{0, 1, 0}, 5)).isEmpty();
            assertThat(adapter.lexicalSearch(otherUserScope, "confidential", 5)).isEmpty();

            KnowledgeSearchScope otherTenantScope = new KnowledgeSearchScope(
                    "other-tenant", "workspace", "kb", "integration-v1", "user");
            KnowledgeSearchScope otherWorkspaceScope = new KnowledgeSearchScope(
                    "tenant", "other-workspace", "kb", "integration-v1", "user");
            assertThat(adapter.lexicalSearch(otherTenantScope, "service attitude", 5)).isEmpty();
            assertThat(adapter.lexicalSearch(otherWorkspaceScope, "service attitude", 5)).isEmpty();
        } finally {
            connectionFactory.destroy();
        }
    }

    private KnowledgeChunk chunk(String id,
                                 String knowledgeBaseId,
                                 String documentId,
                                 String content,
                                 float[] embedding) {
        return new KnowledgeChunk(id, "tenant", "workspace", knowledgeBaseId, 1, documentId, 1,
                documentId + "-version", "integration-v1", 0, content,
                content, "hash-" + id, 3, embedding, 1,
                "text/plain", 1, "Service", "Service", null, null, null, null, null,
                0, content.length(), "{}");
    }
}
