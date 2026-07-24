package com.hmdp.ai.integration;

import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.infrastructure.objectstorage.MinioObjectStorageAdapter;
import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeIngestionIntegrationTest {
    @Container
    static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

    @Container
    static final GenericContainer<?> REDIS_STACK = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.2.0-v10")).withExposedPorts(6379);

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-06-13T22-53-53Z"))
            .withEnv("MINIO_ROOT_USER", "minio-test")
            .withEnv("MINIO_ROOT_PASSWORD", "minio-test-secret")
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000, 9001);

    @Test
    void flywayCreatesKnowledgeSchemaAndInfrastructureRoundTrips() throws Exception {
        MYSQL.migrateSchema();
        JdbcTemplate jdbc = MYSQL.jdbcTemplate();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables " +
                "where table_schema=database() and table_name in ('ai_document','ai_document_chunk'," +
                "'ai_ingestion_job','ai_outbox_event')", Integer.class)).isEqualTo(4);

        LettuceConnectionFactory redis = new LettuceConnectionFactory(
                REDIS_STACK.getHost(), REDIS_STACK.getMappedPort(6379));
        redis.afterPropertiesSet();
        try (RedisConnection connection = redis.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        } finally {
            redis.destroy();
        }

        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient minio = MinioClient.builder().endpoint(endpoint)
                .credentials("minio-test", "minio-test-secret").build();
        String bucket = "hmdp-ai-test";
        byte[] payload = "# Service\nStable and friendly.".getBytes(StandardCharsets.UTF_8);
        MinioObjectStorageAdapter storage = new MinioObjectStorageAdapter(minio, bucket);
        StoredObject stored = storage.put("tenant", "kb", "service.md", "text/markdown", payload, "sha256-test");

        assertThat(minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())).isTrue();
        assertThat(stored.getBucket()).isEqualTo(bucket);
        assertThat(stored.getObjectKey()).endsWith("/sha256-test.md");
        try (InputStream content = storage.get(stored.getBucket(), stored.getObjectKey())) {
            assertThat(content.readAllBytes()).isEqualTo(payload);
        }
        storage.delete(stored.getBucket(), stored.getObjectKey());
        List<String> remainingObjects = new ArrayList<>();
        for (Result<Item> item : minio.listObjects(ListObjectsArgs.builder()
                .bucket(bucket)
                .recursive(true)
                .build())) {
            remainingObjects.add(item.get().objectName());
        }
        assertThat(remainingObjects).doesNotContain(stored.getObjectKey());

        HttpURLConnection health = (HttpURLConnection) new URL(endpoint + "/minio/health/live").openConnection();
        health.setConnectTimeout(3000);
        health.setReadTimeout(3000);
        assertThat(health.getResponseCode()).isEqualTo(200);
    }
}
