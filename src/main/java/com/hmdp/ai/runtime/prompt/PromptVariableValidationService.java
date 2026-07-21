package com.hmdp.ai.runtime.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

@Component
public class PromptVariableValidationService {
    private final ObjectMapper mapper;
    private final PromptVariableResolver resolver;

    public PromptVariableValidationService(ObjectMapper mapper, PromptVariableResolver resolver) {
        this.mapper = mapper;
        this.resolver = resolver;
    }

    public void require(PromptVersion prompt, PromptRenderContext context) {
        try {
            JsonNode schema = mapper.readTree(prompt.getVariablesSchema());
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode item : required) {
                    if (resolver.resolve(item.asText(), context) == null) {
                        throw missing(item.asText());
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().path("required").asBoolean(false)
                        && resolver.resolve(field.getKey(), context) == null) {
                    throw missing(field.getKey());
                }
            }
        } catch (AiPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new AiPlatformException(ErrorCode.AI_EXECUTION_FAILED,
                    "PROMPT_VARIABLE_SCHEMA_INVALID");
        }
    }

    private AiPlatformException missing(String name) {
        return new AiPlatformException(ErrorCode.PROMPT_VARIABLE_MISSING,
                "PROMPT_VARIABLE_MISSING: " + name);
    }
}
