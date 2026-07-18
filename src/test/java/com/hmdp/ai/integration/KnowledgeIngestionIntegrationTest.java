package com.hmdp.ai.integration;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.HttpURLConnection;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeIngestionIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("hmdp").withUsername("hmdp").withPassword("hmdp-test");

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
    void flywayCreatesKnowledgeSchemaAndInfrastructureIsReachable() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables " +
                "where table_schema=database() and table_name in ('ai_document','ai_document_chunk'," +
                "'ai_ingestion_job','ai_outbox_event')", Integer.class)).isEqualTo(4);

        URL redis = new URL("http", REDIS_STACK.getHost(), REDIS_STACK.getMappedPort(6379), "/");
        assertThat(redis.getHost()).isNotBlank();

        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient minio = MinioClient.builder().endpoint(endpoint)
                .credentials("minio-test", "minio-test-secret").build();
        minio.makeBucket(MakeBucketArgs.builder().bucket("hmdp-ai-test").build());
        assertThat(minio.bucketExists(BucketExistsArgs.builder().bucket("hmdp-ai-test").build())).isTrue();

        HttpURLConnection health = (HttpURLConnection) new URL(endpoint + "/minio/health/live").openConnection();
        health.setConnectTimeout(3000);
        health.setReadTimeout(3000);
        assertThat(health.getResponseCode()).isEqualTo(200);
    }
}
