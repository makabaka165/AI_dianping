package com.hmdp.ai.domain.tool;
public interface ToolAuditPort {void record(ToolDefinition definition,ToolInvocation invocation,ToolResult result,long startedAtMillis,long durationMillis);}
