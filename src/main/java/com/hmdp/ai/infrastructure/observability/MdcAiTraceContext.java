package com.hmdp.ai.infrastructure.observability;
import com.hmdp.ai.domain.observability.AiTraceContext;import com.hmdp.ai.domain.run.AgentRunRecord;import org.slf4j.MDC;import org.springframework.stereotype.Component;
@Component public class MdcAiTraceContext implements AiTraceContext {public void bind(AgentRunRecord run,String nodeRunId){put("traceId",run.getTraceId());put("runId",run.getId());
    put("nodeRunId",nodeRunId);put("tenantId",run.getTenantId());put("workspaceId",run.getWorkspaceId());put("agentId",run.getAgentId());}
    public void clear(){for(String key:new String[]{"traceId","runId","nodeRunId","tenantId","workspaceId","agentId"})MDC.remove(key);}
    private void put(String key,String value){if(value==null)MDC.remove(key);else MDC.put(key,value);}}
