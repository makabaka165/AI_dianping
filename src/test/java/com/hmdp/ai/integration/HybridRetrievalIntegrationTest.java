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
import static org.mockito.ArgumentMatchers.anyList;
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
    void redisStackExecutesAclScopedVectorAndLexicalRecall() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS_STACK.getHost(), REDIS_STACK.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findDocumentReadPrincipals(eq("tenant"), eq("workspace"), anyList()))
                .thenReturn(Collections.singletonMap("document", Collections.singletonList("workspace")));
        RedisStackKnowledgeIndexAdapter adapter = new RedisStackKnowledgeIndexAdapter(connectionFactory, repository);
        adapter.ensureIndex("integration-v1", 3);
        adapter.index(Collections.singletonList(chunk()));
        KnowledgeSearchScope scope = new KnowledgeSearchScope("tenant", "workspace", "kb",
                "integration-v1", "user");

        List<IndexHit> vector = adapter.vectorSearch(scope, new float[]{1, 0, 0}, 5);
        List<IndexHit> lexical = adapter.lexicalSearch(scope, "service attitude", 5);

        assertThat(vector).extracting(IndexHit::getChunkId).contains("chunk");
        assertThat(lexical).extracting(IndexHit::getChunkId).contains("chunk");
        connectionFactory.destroy();
    }

    private KnowledgeChunk chunk() {
        return new KnowledgeChunk("chunk", "tenant", "workspace", "kb", 1, "document", 1,
                "document-version", "integration-v1", 0, "excellent service attitude",
                "excellent service attitude", "hash", 3, new float[]{1, 0, 0}, 1,
                "text/plain", 1, "Service", "Service", null, null, null, null, null,
                0, 26, "{}");
    }
}
