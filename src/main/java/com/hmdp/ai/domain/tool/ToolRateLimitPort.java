package com.hmdp.ai.domain.tool;
public interface ToolRateLimitPort {boolean acquire(String tenantId,String toolId,int permitsPerSecond);}
