package com.hmdp.ai.orchestration;

import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.workflow.ChatWorkflow;
import com.hmdp.ai.workflow.CompareWorkflow;
import com.hmdp.ai.workflow.QAWorkflow;
import com.hmdp.ai.workflow.RecommendWorkflow;
import com.hmdp.ai.workflow.SummaryWorkflow;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.entity.ShopSummaryResult;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class ShopAIOrchestrator {

    @Resource
    private ChatWorkflow chatWorkflow;

    @Resource
    private SummaryWorkflow summaryWorkflow;

    @Resource
    private QAWorkflow qaWorkflow;

    @Resource
    private CompareWorkflow compareWorkflow;

    @Resource
    private RecommendWorkflow recommendWorkflow;

    public ShopAIResponse chat(ShopAIRequestContext context, ChatWorkflowRequest request) {
        context.setIntent(ShopAIIntent.FREE_CHAT);
        return chatWorkflow.execute(context, request);
    }

    public ShopSummaryResult summary(ShopAIRequestContext context, SummaryWorkflowRequest request) {
        context.setIntent(ShopAIIntent.SUMMARY);
        return summaryWorkflow.execute(context, request);
    }

    public ShopAIResponse ask(ShopAIRequestContext context, QAWorkflowRequest request) {
        context.setIntent(ShopAIIntent.QA);
        return qaWorkflow.execute(context, request);
    }

    public ShopAIResponse compare(ShopAIRequestContext context, CompareWorkflowRequest request) {
        context.setIntent(ShopAIIntent.COMPARE);
        return compareWorkflow.execute(context, request);
    }

    public ShopAIResponse recommend(ShopAIRequestContext context, RecommendWorkflowRequest request) {
        context.setIntent(ShopAIIntent.RECOMMEND);
        return recommendWorkflow.execute(context, request);
    }
}
