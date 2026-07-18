package com.hmdp.ai.domain.observability;
import com.hmdp.ai.domain.run.AgentRunRecord;
public interface AiTraceContext {void bind(AgentRunRecord run,String nodeRunId);void clear();}
