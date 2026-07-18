package com.hmdp.ai.infrastructure.external;

import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.application.ShopAIApplicationService;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.runtime.agent.AgentOutputAssembler;
import com.hmdp.ai.runtime.model.AgentModelExecutionPort;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.ai.ShopAIResponse;
import org.springframework.stereotype.Component;

@Component
public class ShopCompatibilityExecutionEngine implements AgentModelExecutionPort {
    private static final String SHOP_AGENT_CODE = "shop-consultant";
    private final ShopAIApplicationService shopAi;
    private final AgentOutputAssembler outputAssembler;

    public ShopCompatibilityExecutionEngine(ShopAIApplicationService shopAi,
                                            AgentOutputAssembler outputAssembler) {
        this.shopAi = shopAi;
        this.outputAssembler = outputAssembler;
    }

    @Override
    public AgentRunOutput execute(PublishedAgentDefinition definition, ExecutionContext context,
                                  AgentInputRequest input) {
        if (!SHOP_AGENT_CODE.equals(definition.getAgent().getCode())) {
            throw new AiPlatformException(ErrorCode.AI_EXECUTION_FAILED,
                    "this agent version has no executable workflow adapter");
        }
        long started = System.nanoTime();
        ShopAIResponse response = shopAi.chat(context.getUserId(), context.getSessionId(), input.getText(),
                null, "/api/v1/agent-runs");
        return outputAssembler.fromShopResponse(response, (System.nanoTime() - started) / 1_000_000L);
    }
}
