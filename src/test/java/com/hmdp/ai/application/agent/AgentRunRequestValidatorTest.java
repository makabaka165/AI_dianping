package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.agent.AgentResponseMode;
import com.hmdp.ai.application.dto.agent.AgentRunRequest;
import com.hmdp.ai.application.dto.agent.AttachmentRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunRequestValidatorTest {

    @Test
    void acceptsHttpsKnowledgeAndObjectStorageReferences() {
        AgentRunRequest request = request();
        request.getInput().getReferenceUris().add("https://example.com/reference");
        request.getInput().getReferenceUris().add("kb:document/1");
        request.getInput().getReferenceUris().add("s3:bucket/object");

        assertDoesNotThrow(() -> validator().validate(definition(false), request));
    }

    @Test
    void rejectsFileUriAndImageWhenModelHasNoVisionCapability() {
        AgentRunRequest request = request();
        request.getInput().getReferenceUris().add("file:///etc/passwd");
        AttachmentRequest image = new AttachmentRequest();
        image.setAttachmentId("image-1");
        image.setName("image.png");
        image.setContentType("image/png");
        image.setSizeBytes(100);
        image.setUri("s3:bucket/image.png");
        request.getInput().getAttachments().add(image);

        AiPlatformException error = assertThrows(AiPlatformException.class,
                () -> validator().validate(definition(false), request));

        assertTrue(error.getIssues().stream().anyMatch(issue -> "REFERENCE_URI_SCHEME_DENIED".equals(issue.getCode())));
        assertTrue(error.getIssues().stream().anyMatch(issue -> "MODEL_CAPABILITY_MISMATCH".equals(issue.getCode())));
    }

    private AgentRunRequestValidator validator() {
        ObjectMapper mapper = new ObjectMapper();
        return new AgentRunRequestValidator(mapper, new JsonSchemaValidationService(mapper));
    }

    private AgentRunRequest request() {
        AgentInputRequest input = new AgentInputRequest();
        input.setText("compare shop 1 and shop 2");
        AgentRunRequest request = new AgentRunRequest();
        request.setAgentId("shop-consultant");
        request.setAgentVersion(1);
        request.setSessionId("session-1");
        request.setInput(input);
        request.setResponseMode(AgentResponseMode.STREAM);
        return request;
    }

    private PublishedAgentDefinition definition(boolean vision) {
        AgentDefinition agent = new AgentDefinition("agent", "tenant", "workspace", "shop-consultant",
                "Shop", "description", 1, "ACTIVE", null, null);
        AgentVersion agentVersion = new AgentVersion("agent-v1", "tenant", "workspace", "agent", 1,
                "Shop", "description", "model", "prompt-v1", "workflow-v1", "{}",
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}},\"additionalProperties\":true}",
                "{\"type\":\"object\"}", "{}", "{}", VersionStatus.PUBLISHED,
                "hash", "change", null, null, null);
        ModelProfile model = new ModelProfile("model", "tenant", "workspace", "model", "Model", "provider",
                "name", "https://example.com/v1", "env:AI_CHAT_API_KEY", ModelType.CHAT,
                "{\"streaming\":true,\"toolCalling\":true,\"jsonSchema\":true,\"vision\":" + vision + ",\"longContext\":true}",
                "{}", 32000, 1000, 30000, "{}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                true, 1, "ACTIVE", null, null);
        PromptVersion prompt = new PromptVersion("prompt-v1", "tenant", "workspace", "prompt", 1,
                "system", "task", null, null, null, "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "[]",
                VersionStatus.PUBLISHED, "hash", "change", null, null, null);
        VersionSnapshot snapshot = new VersionSnapshot("agent", 1, "prompt", 1, "workflow", 1,
                "model", 1, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        return new PublishedAgentDefinition(agent, agentVersion, model, prompt, "workflow", 1,
                "PUBLISHED", Collections.emptyList(), Collections.emptyList(), snapshot);
    }
}
