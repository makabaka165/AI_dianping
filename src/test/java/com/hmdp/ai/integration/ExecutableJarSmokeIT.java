package com.hmdp.ai.integration;

import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ExecutableJarSmokeIT {
    @Container
    static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    @Test
    void executableSpringBootJarStartsAgainstContainerInfrastructure() throws Exception {
        MYSQL.migrateSchema();
        Path jar = executableJar();
        Path log = Files.createTempFile(Path.of("target"), "executable-jar-smoke-", ".log");
        int port = availablePort();
        String redisHost = REDIS.getHost();
        String redisPort = String.valueOf(REDIS.getMappedPort(6379));
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-jar", jar.toString(),
                "--server.port=" + port,
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.redis.host=" + redisHost,
                "--spring.redis.port=" + redisPort,
                "--hmdp.ai.redis.business.host=" + redisHost,
                "--hmdp.ai.redis.business.port=" + redisPort,
                "--hmdp.ai.redis.memory.host=" + redisHost,
                "--hmdp.ai.redis.memory.port=" + redisPort,
                "--hmdp.ai.redis.vector.host=" + redisHost,
                "--hmdp.ai.redis.vector.port=" + redisPort,
                "--rag.enabled=false",
                "--rag.data.auto-import=false",
                "--hmdp.ai.task.enabled=false",
                "--hmdp.ai.knowledge.recovery-enabled=false",
                "--hmdp.voucher.order.worker-threads=1",
                "--hmdp.voucher.order.close-worker-threads=1",
                "--hmdp.voucher.order.stream-health-check-enabled=false",
                "--langchain4j.open-ai.chat-model.api-key=jar-smoke-key",
                "--langchain4j.open-ai.streaming-chat-model.api-key=jar-smoke-key",
                "--langchain4j.open-ai.embedding-model.api-key=jar-smoke-key")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            String info = waitForInfo(process, port, log);
            assertThat(info).isNotNull();
            assertThat(process.isAlive()).isTrue();
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private Path executableJar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith(".jar.original"))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("Executable Spring Boot JAR was not produced"));
        }
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String waitForInfo(Process process, int port, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Executable JAR exited before startup:\n" + logTail(log));
            }
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + port + "/actuator/info").openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                if (connection.getResponseCode() == 200) {
                    return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Executable JAR did not start within 120 seconds:\n" + logTail(log), lastFailure);
    }

    private String logTail(Path log) throws IOException {
        String content = Files.readString(log, StandardCharsets.UTF_8);
        return content.substring(Math.max(0, content.length() - 8000));
    }
}
