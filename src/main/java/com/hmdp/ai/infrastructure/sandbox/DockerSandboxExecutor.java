package com.hmdp.ai.infrastructure.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.artifact.ArtifactReference;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.runtime.cancellation.CancellableInvocation;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DockerSandboxExecutor implements SandboxExecutor {
    private final ObjectMapper mapper;
    private final SandboxFileService files;
    private final SandboxArtifactService artifacts;
    private final SandboxImagePolicy images;
    private final RunCancellationRegistry cancellations;

    public DockerSandboxExecutor(ObjectMapper mapper, SandboxFileService files, SandboxArtifactService artifacts,
                                 SandboxImagePolicy images, RunCancellationRegistry cancellations) {
        this.mapper = mapper;
        this.files = files;
        this.artifacts = artifacts;
        this.images = images;
        this.cancellations = cancellations;
    }

    @Override
    public SandboxExecution execute(Path workspace, JsonNode configuration, JsonNode input,
                                    ExecutionContext context, int timeoutMs) {
        Process process = null;
        CancellableInvocation processCancellation = null;
        try {
            String command = input.path("command").asText();
            Set<String> allowedCommands = new LinkedHashSet<>();
            configuration.path("allowedCommands").forEach(value -> allowedCommands.add(value.asText()));
            if (command.isEmpty() || !allowedCommands.contains(command)) {
                throw new IllegalArgumentException("SANDBOX_COMMAND_NOT_ALLOWED");
            }
            String image = configuration.path("image").asText();
            images.requireAllowed(image);
            int maxInput = Math.max(1024, configuration.path("maxInputBytes").asInt(1024 * 1024));
            if (input.path("files").isObject()) {
                input.path("files").fields().forEachRemaining(
                        entry -> files.write(workspace, entry.getKey(), entry.getValue().asText(), maxInput));
            }

            List<String> dockerCommand = dockerCommand(workspace, image, command, configuration, input);
            process = new ProcessBuilder(dockerCommand).start();
            Process running = process;
            processCancellation = () -> terminate(running);
            cancellations.track(context.getRunId(), processCancellation);

            int maxOutput = Math.max(1024,
                    configuration.path("maxOutputBytes").asInt(1024 * 1024));
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> read(running.getInputStream(), maxOutput));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> read(running.getErrorStream(), maxOutput));
            boolean done = running.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (!done) {
                terminate(running);
                throw new IllegalStateException("SANDBOX_TIMEOUT");
            }
            String standardOutput = stdout.get(5, TimeUnit.SECONDS);
            String standardError = stderr.get(5, TimeUnit.SECONDS);
            ObjectNode data = mapper.createObjectNode().put("exitCode", running.exitValue())
                    .put("stdout", standardOutput).put("stderr", standardError);
            List<ArtifactReference> references = artifacts.store(context, workspace,
                    context.getExecutionBudget().getMaxArtifactBytes(),
                    Math.max(1, configuration.path("maxArtifacts").asInt(20)));
            return new SandboxExecution(data, references);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) terminate(process);
            throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
        } catch (java.util.concurrent.CancellationException e) {
            if (process != null) terminate(process);
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SANDBOX_EXECUTION_FAILED", e);
        } finally {
            if (processCancellation != null) {
                cancellations.untrack(context.getRunId(), processCancellation);
            }
            if (process != null && process.isAlive()) terminate(process);
        }
    }

    private List<String> dockerCommand(Path workspace, String image, String command,
                                       JsonNode configuration, JsonNode input) {
        String user = configuration.path("user").asText("65532:65532");
        if (!user.matches("[0-9]{1,10}(:[0-9]{1,10})?")) {
            throw new IllegalArgumentException("SANDBOX_USER_INVALID");
        }
        List<String> result = new ArrayList<>(Arrays.asList(
                "docker", "run", "--rm", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--user", user,
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m", "--network", "none",
                "--cpus", String.valueOf(Math.max(0.1, configuration.path("cpus").asDouble(1.0))),
                "--memory", Math.max(64, configuration.path("memoryMb").asInt(256)) + "m",
                "--pids-limit", String.valueOf(Math.max(16,
                        configuration.path("pidsLimit").asInt(64))),
                "-v", workspace.toString() + ":/workspace:rw", "-w", "/workspace", image, command));
        if (input.path("args").isArray()) {
            for (JsonNode argument : input.path("args")) {
                String value = argument.asText();
                if (value.length() > 256 || value.matches(".*[\\r\\n\\0].*")) {
                    throw new IllegalArgumentException("SANDBOX_ARGUMENT_INVALID");
                }
                result.add(value);
            }
        }
        return result;
    }

    private String read(InputStream stream, int maxBytes) {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maxBytes) throw new IllegalArgumentException("SANDBOX_OUTPUT_TOO_LARGE");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SANDBOX_OUTPUT_READ_FAILED", e);
        }
    }

    private void terminate(Process process) {
        try {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
