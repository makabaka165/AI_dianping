package com.hmdp.ai.domain.mcp;
public interface McpManagementPort {String checkHealth(McpServer server);int syncTools(McpServer server,String actorId);}
