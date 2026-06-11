package com.hmdp.ai.workflow;

import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.orchestration.ShopAIRequestContext;

public interface ShopAIWorkflow<I, O> {
    ShopAIIntent intent();

    O execute(ShopAIRequestContext context, I request);
}
