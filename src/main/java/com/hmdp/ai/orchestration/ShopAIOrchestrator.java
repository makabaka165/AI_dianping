package com.hmdp.ai.orchestration;

import com.hmdp.ai.workflow.ChatWorkflow;
import com.hmdp.ai.workflow.CompareWorkflow;
import com.hmdp.ai.workflow.QAWorkflow;
import com.hmdp.ai.workflow.QualitySummaryWorkflow;
import com.hmdp.ai.workflow.RecommendWorkflow;
import com.hmdp.ai.workflow.SummaryWorkflow;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.QualitySummaryWorkflowRequest;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.dto.ai.ShopSummaryResult;
import com.hmdp.ai.infra.AiMetricsService;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;

@Service
public class ShopAIOrchestrator {

    @Resource
    private ChatWorkflow chatWorkflow;

    @Resource
    private SummaryWorkflow summaryWorkflow;

    @Resource
    private QualitySummaryWorkflow qualitySummaryWorkflow;

    @Resource
    private QAWorkflow qaWorkflow;

    @Resource
    private CompareWorkflow compareWorkflow;

    @Resource
    private RecommendWorkflow recommendWorkflow;

    @Resource
    private AiMetricsService aiMetricsService;

    public ShopAIResponse chat(ShopAIRequestContext context, ChatWorkflowRequest request) {
        long start = System.currentTimeMillis();
        context.setIntent(ShopAIIntent.FREE_CHAT);
        try {
            ShopAIResponse response = chatWorkflow.execute(context, request);
            recordResponse(context, response, start, "success");
            return response;
        } catch (RuntimeException e) {
            recordFailure(context, start);
            throw e;
        }
    }

    public Flux<ServerSentEvent<ShopAIStreamEvent>> chatStream(ShopAIRequestContext context, ChatWorkflowRequest request) {
        return Flux.defer(() -> {
            long start = System.currentTimeMillis();
            context.setIntent(ShopAIIntent.FREE_CHAT);
            return chatWorkflow.stream(context, request)
                    .doOnComplete(() -> recordRequest(context, start, false, false, "success"))
                    .doOnError(e -> recordRequest(context, start, true, false, "failure"));
        });
    }

    public ShopSummaryResult summary(ShopAIRequestContext context, SummaryWorkflowRequest request) {
        long start = System.currentTimeMillis();
        context.setIntent(ShopAIIntent.SUMMARY);
        try {
            ShopSummaryResult response = summaryWorkflow.execute(context, request);
            recordSummary(context, response, start, "success");
            return response;
        } catch (RuntimeException e) {
            recordFailure(context, start);
            throw e;
        }
    }

    public ShopSummaryResult qualitySummary(ShopAIRequestContext context, QualitySummaryWorkflowRequest request) {
        long start = System.currentTimeMillis();
        context.setIntent(ShopAIIntent.SUMMARY);
        try {
            ShopSummaryResult response = qualitySummaryWorkflow.execute(context, request);
            recordSummary(context, response, start, "success");
            return response;
        } catch (RuntimeException e) {
            recordFailure(context, start);
            throw e;
        }
    }

    public ShopAIResponse ask(ShopAIRequestContext context, QAWorkflowRequest request) {
        long start = System.currentTimeMillis();
        context.setIntent(ShopAIIntent.QA);
        try {
            ShopAIResponse response = qaWorkflow.execute(context, request);
            recordResponse(context, response, start, "success");
            return response;
        } catch (RuntimeException e) {
            recordFailure(context, start);
            throw e;
        }
    }

    public ShopAIResponse compare(ShopAIRequestContext context, CompareWorkflowRequest request) {
        long start = System.currentTimeMillis();
        context.setIntent(ShopAIIntent.COMPARE);
        try {
            ShopAIResponse response = compareWorkflow.execute(context, request);
            recordResponse(context, response, start, "success");
            return response;
        } catch (RuntimeException e) {
            recordFailure(context, start);
            throw e;
        }
    }

    public ShopAIResponse recommend(ShopAIRequestContext context, RecommendWorkflowRequest request) {
        long start = System.currentTimeMillis();
        context.setIntent(ShopAIIntent.RECOMMEND);
        try {
            ShopAIResponse response = recommendWorkflow.execute(context, request);
            recordResponse(context, response, start, "success");
            return response;
        } catch (RuntimeException e) {
            recordFailure(context, start);
            throw e;
        }
    }

    private void recordResponse(ShopAIRequestContext context, ShopAIResponse response, long start, String result) {
        boolean degraded = response != null && Boolean.TRUE.equals(response.getDegraded());
        boolean cacheHit = response != null && Boolean.TRUE.equals(response.getCacheHit());
        recordRequest(context, start, degraded, cacheHit, result);
    }

    private void recordSummary(ShopAIRequestContext context, ShopSummaryResult response, long start, String result) {
        boolean degraded = response != null && Boolean.TRUE.equals(response.getDegraded());
        boolean cacheHit = response != null && Boolean.TRUE.equals(response.getCacheHit());
        recordRequest(context, start, degraded, cacheHit, result);
    }

    private void recordFailure(ShopAIRequestContext context, long start) {
        recordRequest(context, start, true, false, "failure");
    }

    private void recordRequest(ShopAIRequestContext context,
                               long start,
                               boolean degraded,
                               boolean cacheHit,
                               String result) {
        if (aiMetricsService == null) {
            return;
        }
        ShopAIIntent intent = context == null ? null : context.getIntent();
        String analysisType = intent == null ? "unknown" : intent.name().toLowerCase();
        aiMetricsService.recordRequestDuration(analysisType,
                intent == null ? null : intent.name(),
                null,
                System.currentTimeMillis() - start,
                degraded,
                cacheHit,
                result);
    }
}
