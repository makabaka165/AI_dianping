package com.hmdp.ai.domain.external;
import com.fasterxml.jackson.databind.JsonNode;
public interface DifyWorkflowGateway {JsonNode run(JsonNode configuration,JsonNode input,String userId,String runId,int timeoutMs);}
