package com.hmdp.ai.runtime.tool;
import com.fasterxml.jackson.databind.JsonNode;import com.hmdp.ai.domain.run.ExecutionContext;
public interface LocalSkill {JsonNode execute(ExecutionContext context,JsonNode input);}
