package com.hmdp.ai.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.mcp.McpServer;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolProtocolAdapter;
import com.hmdp.ai.domain.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class McpToolProtocolAdapter implements ToolProtocolAdapter {
    private final McpServerRegistry servers;
    private final McpToolExecutor executor;

    public McpToolProtocolAdapter(McpServerRegistry servers, McpToolExecutor executor) {
        this.servers = servers;
        this.executor = executor;
    }

    public ToolProtocol protocol() {
        return ToolProtocol.MCP;
    }

    public ToolResult execute(ToolDefinition definition, ToolInvocation invocation, JsonNode configuration) {
        long started = System.currentTimeMillis();
        try {
            McpServer server = servers.require(invocation.getContext().getTenantId(),
                    invocation.getContext().getWorkspaceId(), configuration.path("serverId").asText());
            String tool = configuration.path("toolName").asText();
            if (tool.isEmpty()) throw new IllegalArgumentException("MCP_TOOL_NAME_REQUIRED");
            return ToolResult.success(executor.execute(server, tool, invocation.getInput(),
                    invocation.getContext().getRunId()), System.currentTimeMillis() - started);
        } catch (java.util.concurrent.CancellationException e) {
            return ToolResult.failure(ToolCallStatus.CANCELLED, "RUN_CANCELLED", "run cancelled", false);
        } catch (Exception e) {
            String code = code(e);
            return ToolResult.failure(ToolCallStatus.FAILED, code, "MCP tool execution failed",
                    !code.matches("MCP_SERVER_(NOT_FOUND|DISABLED)"));
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
        return "MCP_CALL_FAILED";
    }
}
