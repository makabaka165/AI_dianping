package com.hmdp.ai.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ToolProtocolAdapter {
    ToolProtocol protocol();

    ToolResult execute(ToolDefinition definition, ToolInvocation invocation, JsonNode configuration);
}
