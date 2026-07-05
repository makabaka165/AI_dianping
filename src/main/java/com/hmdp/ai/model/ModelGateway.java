package com.hmdp.ai.model;

import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.ai.port.AiModelServicePort;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIAnalysisResult;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.Callable;

@Component
public class ModelGateway {

    public static final String DEFAULT_MODEL_NAME = "configured-chat-model";

    @Resource
    private AiModelServicePort aiModelServicePort;

    @Resource
    private ModelResilienceExecutor resilienceExecutor;

    @Resource
    private StructuredOutputParser structuredOutputParser;

    @Value("${langchain4j.open-ai.chat-model.model-name:" + DEFAULT_MODEL_NAME + "}")
    private String configuredModelName;

    @Resource
    private RepairPromptFactory repairPromptFactory;

    @Resource
    private ModelMetricsRecorder modelMetricsRecorder;

    public String modelName() {
        return configuredModelName == null || configuredModelName.trim().isEmpty()
                ? DEFAULT_MODEL_NAME
                : configuredModelName.trim();
    }

    public ShopAIAnalysisResult generateStructuredSummary(String prompt, ShopAnalysisContext context) throws Exception {
        String json = executeText("generateStructuredAnalysis", prompt,
                () -> modelService().generateStructuredAnalysis(prompt));
        try {
            return parser().parseStructuredAnalysis(json);
        } catch (Exception e) {
            return repairStructuredSummary(prompt, context, "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopAIAnalysisResult repairStructuredSummary(String prompt,
                                                        ShopAnalysisContext context,
                                                        String qualityReason) throws Exception {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 summary、sentiment、keywords、pros、cons、confidence、evidenceIds。");
        String json = executeText("generateStructuredAnalysis.repair", repairPrompt,
                () -> modelService().repairStructuredAnalysis(repairPrompt));
        return parser().parseStructuredAnalysis(json);
    }

    public ShopQAResult generateStructuredAnswer(String memoryId,
                                                 String prompt,
                                                 Long shopId,
                                                 String question,
                                                 List<EvidenceItem> evidence) {
        String json = executeTextUnchecked("ask:analyzeShopData", prompt,
                () -> modelService().analyzeShopData(memoryId, prompt));
        try {
            return parser().parseQA(json, shopId, question);
        } catch (Exception e) {
            return repairStructuredAnswer(memoryId, prompt, shopId, question, "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopQAResult repairStructuredAnswer(String memoryId,
                                               String prompt,
                                               Long shopId,
                                               String question,
                                               String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 shopId、question、answer、evidenceIds、insufficientEvidence。");
        String json = executeTextUnchecked("ask:analyzeShopData.repair", repairPrompt,
                () -> modelService().repairAnalyzeShopData(memoryId, repairPrompt));
        return parser().parseQA(json, shopId, question);
    }

    public ShopCompareResult generateStructuredComparison(String memoryId,
                                                         String prompt,
                                                         Long shopId1,
                                                         Long shopId2,
                                                         String aspect,
                                                         List<EvidenceItem> evidence) {
        String json = executeTextUnchecked("compare:analyzeShopData", prompt,
                () -> modelService().analyzeShopData(memoryId, prompt));
        try {
            return parser().parseCompare(json, shopId1, shopId2, aspect);
        } catch (Exception e) {
            return repairStructuredComparison(memoryId, prompt, shopId1, shopId2, aspect,
                    "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopCompareResult repairStructuredComparison(String memoryId,
                                                       String prompt,
                                                       Long shopId1,
                                                       Long shopId2,
                                                       String aspect,
                                                       String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 shopId1、shopId2、aspect、conclusion、winnerByAspect、shop1Score、shop2Score、shop1Pros、shop2Pros、riskNotes、evidenceIds。");
        String json = executeTextUnchecked("compare:analyzeShopData.repair", repairPrompt,
                () -> modelService().repairAnalyzeShopData(memoryId, repairPrompt));
        return parser().parseCompare(json, shopId1, shopId2, aspect);
    }

    public ShopRecommendResult generateStructuredRecommendation(String memoryId,
                                                                String prompt,
                                                                String userPreference,
                                                                String category,
                                                                List<ShopView> candidates,
                                                                List<EvidenceItem> evidence) {
        String json = executeTextUnchecked("recommend:analyzeShopData", prompt,
                () -> modelService().analyzeShopData(memoryId, prompt));
        try {
            return parser().parseRecommend(json, userPreference, category, candidates);
        } catch (Exception e) {
            return repairStructuredRecommendation(memoryId, prompt, userPreference, category, candidates,
                    "JSON 解析失败：" + e.getMessage());
        }
    }

    public ShopRecommendResult repairStructuredRecommendation(String memoryId,
                                                              String prompt,
                                                              String userPreference,
                                                              String category,
                                                              List<ShopView> candidates,
                                                              String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新输出严格 JSON，字段必须包含 userPreference、category、message、items，items 内包含 rank、shopId、shopName、address、reason、suitableFor、uncertainty、evidenceIds、confidence。");
        String json = executeTextUnchecked("recommend:analyzeShopData.repair", repairPrompt,
                () -> modelService().repairAnalyzeShopData(memoryId, repairPrompt));
        return parser().parseRecommend(json, userPreference, category, candidates);
    }

    public String generateFreeChat(String memoryId, String prompt) {
        return executeTextUnchecked("freeChat", prompt,
                () -> modelService().chat(memoryId, prompt));
    }

    public String repairFreeChat(String memoryId, String prompt, String qualityReason) {
        String repairPrompt = repairPrompt(prompt, qualityReason,
                "请重新生成一个简洁、安全、只说明能力范围或追问必要参数的回答。");
        return executeTextUnchecked("freeChat.repair", repairPrompt,
                () -> modelService().repairChat(memoryId, repairPrompt));
    }

    public IntentRouteCandidate classifyIntent(String prompt) throws Exception {
        String json = executeText("classifyIntent", prompt,
                () -> modelService().classifyIntent(prompt));
        return parser().parseIntent(json);
    }

    public Flux<String> streamChat(String memoryId, String message) {
        return executor().decorateStream(modelService().freeChatStream(memoryId, message));
    }

    public Flux<String> streamAnswer(String memoryId, String prompt) {
        return executor().decorateStream(modelService().chatStream(memoryId, prompt));
    }

    public Flux<String> streamComparison(String memoryId, String prompt) {
        return executor().decorateStream(modelService().chatStream(memoryId, prompt));
    }

    public Flux<String> streamRecommendation(String memoryId, String prompt) {
        return executor().decorateStream(modelService().chatStream(memoryId, prompt));
    }

    private <T> T execute(String operation, Callable<T> callable) throws Exception {
        return executor().execute(operation, callable);
    }

    private String executeText(String operation, String input, Callable<String> callable) throws Exception {
        long start = System.currentTimeMillis();
        try {
            String output = execute(operation, callable);
            metrics().record(operation, modelName(), input, output, System.currentTimeMillis() - start, true);
            return output;
        } catch (Exception e) {
            metrics().record(operation, modelName(), input, null, System.currentTimeMillis() - start, false);
            throw e;
        }
    }

    private <T> T executeUnchecked(String operation, Callable<T> callable) {
        try {
            return execute(operation, callable);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelGatewayException(operation, e);
        }
    }

    private String executeTextUnchecked(String operation, String input, Callable<String> callable) {
        try {
            return executeText(operation, input, callable);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelGatewayException(operation, e);
        }
    }

    private String repairPrompt(String originalPrompt, String qualityReason, String instruction) {
        return repairFactory().repairPrompt(originalPrompt, qualityReason, instruction);
    }

    private StructuredOutputParser parser() {
        if (structuredOutputParser == null) {
            structuredOutputParser = new StructuredOutputParser();
        }
        return structuredOutputParser;
    }

    private ModelResilienceExecutor executor() {
        if (resilienceExecutor == null) {
            resilienceExecutor = new ModelResilienceExecutor();
        }
        return resilienceExecutor;
    }

    private RepairPromptFactory repairFactory() {
        if (repairPromptFactory == null) {
            repairPromptFactory = new RepairPromptFactory();
        }
        return repairPromptFactory;
    }

    private ModelMetricsRecorder metrics() {
        if (modelMetricsRecorder == null) {
            modelMetricsRecorder = new ModelMetricsRecorder();
        }
        return modelMetricsRecorder;
    }

    private AiModelServicePort modelService() {
        return aiModelServicePort;
    }
}
