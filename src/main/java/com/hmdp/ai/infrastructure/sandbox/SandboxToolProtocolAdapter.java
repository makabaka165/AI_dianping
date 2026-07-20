package com.hmdp.ai.infrastructure.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolProtocolAdapter;
import com.hmdp.ai.domain.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;

@Component
public class SandboxToolProtocolAdapter implements ToolProtocolAdapter {
    private final SandboxWorkspaceService workspaces;
    private final DockerSandboxExecutor executor;

    public SandboxToolProtocolAdapter(SandboxWorkspaceService workspaces, DockerSandboxExecutor executor) {
        this.workspaces = workspaces;
        this.executor = executor;
    }

    public ToolProtocol protocol() {
        return ToolProtocol.SANDBOX;
    }

    public ToolResult execute(ToolDefinition definition, ToolInvocation invocation, JsonNode configuration) {
        long started = System.currentTimeMillis();
        Path workspace = workspaces.create(invocation.getContext().getRunId());
        try {
            SandboxExecutor.SandboxExecution result = executor.execute(workspace, configuration,
                    invocation.getInput(), invocation.getContext(), definition.getTimeoutMs());
            return ToolResult.success(result.getData(), Collections.emptyList(), result.getArtifacts(),
                    Collections.emptyList(), UsageSummary.empty(System.currentTimeMillis() - started));
        } catch (java.util.concurrent.CancellationException e) {
            return ToolResult.failure(ToolCallStatus.CANCELLED, "RUN_CANCELLED", "run cancelled", false);
        } catch (Exception e) {
            String code = code(e);
            ToolCallStatus status = code.equals("SANDBOX_TIMEOUT")
                    ? ToolCallStatus.TIMED_OUT : ToolCallStatus.FAILED;
            return ToolResult.failure(status, code, "sandbox execution failed", false);
        }
    }

    private String code(Exception error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().matches("[A-Z0-9_]+")) {
                return cause.getMessage();
            }
            cause = cause.getCause();
        }
        return "SANDBOX_EXECUTION_FAILED";
    }
}
