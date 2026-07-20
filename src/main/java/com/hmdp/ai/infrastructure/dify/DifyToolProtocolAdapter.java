package com.hmdp.ai.infrastructure.dify;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolProtocolAdapter;
import com.hmdp.ai.domain.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class DifyToolProtocolAdapter implements ToolProtocolAdapter {
    private final DifyToolExecutor executor;

    public DifyToolProtocolAdapter(DifyToolExecutor executor) {
        this.executor = executor;
    }

    public ToolProtocol protocol() {
        return ToolProtocol.DIFY;
    }

    public ToolResult execute(ToolDefinition definition, ToolInvocation invocation, JsonNode configuration) {
        long started = System.currentTimeMillis();
        try {
            InvocationContext context = InvocationContext.from(invocation.getContext(),
                    invocation.getNodeRunId(), invocation.getCallId());
            return ToolResult.success(executor.execute(configuration, invocation.getInput(), context,
                    definition.getTimeoutMs()), System.currentTimeMillis() - started);
        } catch (java.util.concurrent.CancellationException e) {
            return ToolResult.failure(ToolCallStatus.CANCELLED, "RUN_CANCELLED", "run cancelled", false);
        } catch (Exception e) {
            String code = code(e);
            return ToolResult.failure(ToolCallStatus.FAILED, code, "Dify execution failed",
                    !code.equals("DIFY_PROVIDER_NOT_CONFIGURED"));
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
        return "DIFY_EXECUTION_FAILED";
    }
}
