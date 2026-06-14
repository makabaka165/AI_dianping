package com.hmdp.ai.intent;

import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.IntentRouteSource;
import com.hmdp.dto.ai.IntentSlotState;
import com.hmdp.dto.ai.ShopAIIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class LLMIntentClassifier {

    @Resource
    private PromptTemplateRegistry promptTemplateRegistry;

    @Resource
    private ModelGateway modelGateway;

    public IntentRouteCandidate classify(String message, IntentRouteCandidate ruleCandidate, IntentSlotState slotState) {
        try {
            String prompt = promptTemplateRegistry.intentClassificationPrompt(message, ruleCandidate, slotState);
            IntentRouteCandidate candidate = modelGateway.classifyIntent(prompt);
            candidate.setSource(IntentRouteSource.LLM);
            return candidate;
        } catch (Exception e) {
            log.debug("LLM intent classification failed", e);
            return IntentRouteCandidate.builder()
                    .intent(ShopAIIntent.UNSUPPORTED)
                    .confidence(0.0)
                    .source(IntentRouteSource.LLM)
                    .clarification("我没有理解你的需求，请补充店铺ID、对比对象或推荐偏好。")
                    .build();
        }
    }
}
