package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.ai.quota.AiQuotaExceededException;
import com.hmdp.ai.quota.AiUserQuotaService;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.service.ai.AIService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * AI测试控制器
 * 职责：提供AI服务的测试、调试和监控接口
 * 路径：/api/ai/test/* 明确标识为测试接口
 * 注意：此控制器仅用于开发测试，不参与生产业务逻辑
 */
@RestController
@RequestMapping("/api/ai/test")
@Profile({"local", "dev", "test"})
@SaCheckPermission("ai:test")
@Slf4j
public class AITestController {

    @Autowired
    private AIService aiService;

    @Autowired
    private AiUserQuotaService aiUserQuotaService;

    // ========== 健康检查和基础测试 ==========

    /**
     * AI服务健康检查
     * GET /api/ai/test/health
     */
    @GetMapping("/health")
    public Result healthCheck() {
        try {
            checkQuota("ai-test-health");
            long startTime = System.currentTimeMillis();
            String response = aiService.healthCheck("请回复：AI服务正常");
            long responseTime = System.currentTimeMillis() - startTime;

            boolean isHealthy = response.contains("正常") || response.contains("AI");

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("status", isHealthy ? "healthy" : "unhealthy");
            resultData.put("response", response);
            resultData.put("responseTime", responseTime + "ms");
            resultData.put("timestamp", System.currentTimeMillis());

            log.info("AI健康检查 - 状态: {}, 响应时间: {}ms", isHealthy ? "正常" : "异常", responseTime);

            return Result.ok(resultData);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("AI服务健康检查失败", e);
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("status", "error");
            resultData.put("error", "AI test endpoint failed");
            resultData.put("timestamp", System.currentTimeMillis());

            Result result = Result.fail("AI服务不可用");
            result.setData(resultData);
            return result;
        }
    }

    /**
     * 基础聊天测试（无记忆）
     * GET /api/ai/test/chat?message=你好
     */
    @GetMapping("/chat")
    public Result testBasicChat(@RequestParam String message) {
        try {
            checkQuota("ai-test-chat");
            log.info("基础聊天测试 - 消息长度: {}", lengthOf(message));

            long startTime = System.currentTimeMillis();
            String response = aiService.testChat(message);
            long responseTime = System.currentTimeMillis() - startTime;

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("requestLength", lengthOf(message));
            resultData.put("response", response);
            resultData.put("responseTime", responseTime + "ms");
            resultData.put("timestamp", System.currentTimeMillis());

            log.info("基础聊天测试完成 - 响应时间: {}ms", responseTime);

            return Result.ok(resultData);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("基础聊天测试失败 - 消息长度: {}", lengthOf(message), e);
            return Result.fail("聊天测试失败");
        }
    }

    /**
     * 记忆功能测试
     * GET /api/ai/test/memory?memoryId=test001&message=我叫张三
     */
    @GetMapping("/memory")
    public Result testMemory(
            @RequestParam(defaultValue = "test_memory") String memoryId,
            @RequestParam String message) {
        try {
            checkQuota("ai-test-memory");
            log.info("记忆功能测试 - memoryId: {}, 消息长度: {}", safeKey(memoryId), lengthOf(message));

            long startTime = System.currentTimeMillis();
            String response = aiService.testMemory(memoryId, message);
            long responseTime = System.currentTimeMillis() - startTime;

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("memoryId", safeKey(memoryId));
            resultData.put("requestLength", lengthOf(message));
            resultData.put("response", response);
            resultData.put("responseTime", responseTime + "ms");
            resultData.put("timestamp", System.currentTimeMillis());

            log.info("记忆功能测试完成 - memoryId: {}, 响应时间: {}ms", safeKey(memoryId), responseTime);

            return Result.ok(resultData);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("记忆功能测试失败 - memoryId: {}, 消息长度: {}", safeKey(memoryId), lengthOf(message), e);
            return Result.fail("记忆测试失败");
        }
    }

    // ========== 原子功能测试 ==========

    /**
     * 情感分析测试
     * POST /api/ai/test/sentiment
     * Body: {"content": "这家店很好"}
     * 注意：此方法仅用于测试，生产环境请使用AnalysisService
     */
    @PostMapping("/sentiment")
    public Result testSentiment(@RequestBody Map<String, String> request) {
        try {
            checkQuota("ai-test-sentiment");
            String content = request.get("content");
            if (content == null || content.trim().isEmpty()) {
                return Result.fail("测试内容不能为空");
            }

            log.info("情感分析测试 - 内容长度: {}", content.length());

            long startTime = System.currentTimeMillis();

            // ✅ 测试基础情感分析（仅用于测试）
            String basicSentiment = aiService.analyzeSentiment(content);

            // 测试详细情感分析
            String detailedSentiment = aiService.testSentimentAnalysisDetailed(content);

            long responseTime = System.currentTimeMillis() - startTime;

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("contentLength", lengthOf(content));
            resultData.put("basicSentiment", basicSentiment);
            resultData.put("detailedSentiment", detailedSentiment);
            resultData.put("responseTime", responseTime + "ms");
            resultData.put("timestamp", System.currentTimeMillis());
            resultData.put("note", "⚠️ 此接口仅用于测试，生产环境请使用AnalysisService");

            log.info("情感分析测试完成 - 基础结果: {}, 响应时间: {}ms", basicSentiment, responseTime);

            return Result.ok(resultData);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("情感分析测试失败", e);
            return Result.fail("情感分析测试失败");
        }
    }

    /**
     * 关键词提取测试
     * POST /api/ai/test/keywords
     * Body: {"content": "这家店环境很好，服务态度也不错，值得推荐"}
     * 注意：此方法仅用于测试，生产环境请使用AnalysisService
     */
    @PostMapping("/keywords")
    public Result testKeywords(@RequestBody Map<String, String> request) {
        try {
            checkQuota("ai-test-keywords");
            String content = request.get("content");
            if (content == null || content.trim().isEmpty()) {
                return Result.fail("测试内容不能为空");
            }

            log.info("关键词提取测试 - 内容长度: {}", content.length());

            long startTime = System.currentTimeMillis();
            // ✅ 测试关键词提取（仅用于测试）
            String keywords = aiService.extractKeywords(content);
            long responseTime = System.currentTimeMillis() - startTime;

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("contentLength", lengthOf(content));
            resultData.put("keywords", keywords);
            resultData.put("keywordsList", keywords.split("[,，]"));  // 分割为数组
            resultData.put("responseTime", responseTime + "ms");
            resultData.put("timestamp", System.currentTimeMillis());
            resultData.put("note", "⚠️ 此接口仅用于测试，生产环境请使用AnalysisService");

            log.info("关键词提取测试完成 - 结果: {}, 响应时间: {}ms", keywords, responseTime);

            return Result.ok(resultData);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("关键词提取测试失败", e);
            return Result.fail("关键词提取测试失败");
        }
    }

    /**
     * 文本总结测试
     * POST /api/ai/test/summarize
     * Body: {"content": "长文本内容..."}
     */
    @PostMapping("/summarize")
    public Result testSummarize(@RequestBody Map<String, String> request) {
        try {
            checkQuota("ai-test-summarize");
            String content = request.get("content");
            if (content == null || content.trim().isEmpty()) {
                return Result.fail("测试内容不能为空");
            }

            log.info("文本总结测试 - 内容长度: {}", content.length());

            long startTime = System.currentTimeMillis();
            String summary = aiService.testSummarize(content);
            long responseTime = System.currentTimeMillis() - startTime;

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("contentLength", lengthOf(content));
            resultData.put("summary", summary);
            resultData.put("responseTime", responseTime + "ms");
            resultData.put("timestamp", System.currentTimeMillis());
            resultData.put("note", "⚠️ 此接口仅用于测试，生产环境请使用AnalysisService");

            log.info("文本总结测试完成 - 响应时间: {}ms", responseTime);

            return Result.ok(resultData);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("文本总结测试失败", e);
            return Result.fail("文本总结测试失败");
        }
    }

    // ========== 综合测试 ==========

    /**
     * 批量功能测试
     * GET /api/ai/test/batch
     */
    @GetMapping("/batch")
    public Result batchTest() {
        Map<String, Object> testResults = new HashMap<>();
        long totalStartTime = System.currentTimeMillis();

        try {
            AIService aiService = quotaCheckedAiService("ai-test-batch");
            log.info("开始执行AI服务批量测试");

            // 1. 测试基础聊天
            try {
                long startTime = System.currentTimeMillis();
                String chatResult = aiService.testChat("你好，这是聊天测试");
                long chatTime = System.currentTimeMillis() - startTime;
                testResults.put("basicChat", Map.of(
                        "result", chatResult,
                        "responseTime", chatTime + "ms",
                        "status", "success"
                ));
                log.debug("基础聊天测试完成: {}ms", chatTime);
            } catch (Exception e) {
                testResults.put("basicChat", Map.of(
                        "error", "failed",
                        "status", "failed"
                ));
                log.warn("基础聊天测试失败", e);
            }

            // 2. 测试情感分析
            try {
                long startTime = System.currentTimeMillis();
                String sentimentResult = aiService.analyzeSentiment("这家店很棒，强烈推荐！");
                long sentimentTime = System.currentTimeMillis() - startTime;
                testResults.put("sentiment", Map.of(
                        "result", sentimentResult,
                        "responseTime", sentimentTime + "ms",
                        "status", "success"
                ));
                log.debug("情感分析测试完成: {}ms", sentimentTime);
            } catch (Exception e) {
                testResults.put("sentiment", Map.of(
                        "error", "failed",
                        "status", "failed"
                ));
                log.warn("情感分析测试失败", e);
            }

            // 3. 测试关键词提取
            try {
                long startTime = System.currentTimeMillis();
                String keywordsResult = aiService.extractKeywords("环境优雅，服务周到，价格合理，味道不错");
                long keywordsTime = System.currentTimeMillis() - startTime;
                testResults.put("keywords", Map.of(
                        "result", keywordsResult,
                        "responseTime", keywordsTime + "ms",
                        "status", "success"
                ));
                log.debug("关键词提取测试完成: {}ms", keywordsTime);
            } catch (Exception e) {
                testResults.put("keywords", Map.of(
                        "error", "failed",
                        "status", "failed"
                ));
                log.warn("关键词提取测试失败", e);
            }

            // 4. 测试记忆功能
            try {
                String batchMemoryId = "batch_test_" + System.currentTimeMillis();
                long startTime = System.currentTimeMillis();

                String memoryResult1 = aiService.testMemory(batchMemoryId, "我叫张三，来自北京");
                String memoryResult2 = aiService.testMemory(batchMemoryId, "我叫什么名字？来自哪里？");

                long memoryTime = System.currentTimeMillis() - startTime;
                testResults.put("memory", Map.of(
                        "step1", memoryResult1,
                        "step2", memoryResult2,
                        "responseTime", memoryTime + "ms",
                        "status", "success"
                ));
                log.debug("记忆功能测试完成: {}ms", memoryTime);
            } catch (Exception e) {
                testResults.put("memory", Map.of(
                        "error", "failed",
                        "status", "failed"
                ));
                log.warn("记忆功能测试失败", e);
            }

            // 5. 测试文本总结
            try {
                long startTime = System.currentTimeMillis();
                String summarizeResult = aiService.testSummarize("这是一段用于测试的长文本内容，包含多个要点和信息，用于验证AI的总结能力。");
                long summarizeTime = System.currentTimeMillis() - startTime;
                testResults.put("summarize", Map.of(
                        "result", summarizeResult,
                        "responseTime", summarizeTime + "ms",
                        "status", "success"
                ));
                log.debug("文本总结测试完成: {}ms", summarizeTime);
            } catch (Exception e) {
                testResults.put("summarize", Map.of(
                        "error", "failed",
                        "status", "failed"
                ));
                log.warn("文本总结测试失败", e);
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;

            // 统计成功率
            long successCount = testResults.values().stream()
                    .mapToLong(result -> {
                        if (result instanceof Map) {
                            Map<?, ?> resultMap = (Map<?, ?>) result;
                            return "success".equals(resultMap.get("status")) ? 1 : 0;
                        }
                        return 0;
                    })
                    .sum();
            int totalTests = testResults.size();
            long failureCount = totalTests - successCount;
            String successRate = totalTests == 0
                    ? "0.0%"
                    : String.format("%.1f%%", (double) successCount / totalTests * 100);

            testResults.put("summary", Map.of(
                    "totalTests", totalTests,
                    "successCount", successCount,
                    "failureCount", failureCount,
                    "successRate", successRate,
                    "totalTime", totalTime + "ms",
                    "timestamp", System.currentTimeMillis(),
                    "note", "⚠️ 这些是测试功能，生产环境请使用对应的业务服务"
            ));

            log.info("AI服务批量测试完成 - 成功: {}/{}, 总耗时: {}ms",
                    successCount, totalTests, totalTime);

            return Result.ok(testResults);
        } catch (QuotaExceededSignal e) {
            return quotaFailure(e.getQuotaException());
        } catch (Exception e) {
            log.error("批量测试执行失败", e);
                testResults.put("error", "failed");
            testResults.put("timestamp", System.currentTimeMillis());
            return Result.ok(testResults);  // 即使测试失败也返回结果
        }
    }

    /**
     * 性能压力测试
     * GET /api/ai/test/stress?count=10
     */
    @GetMapping("/stress")
    public Result stressTest(@RequestParam(defaultValue = "5") Integer count) {
        if (count == null || count <= 0) {
            return Result.fail("stress test count must be greater than 0");
        }
        if (count > 20) {
            return Result.fail("压力测试次数不能超过20次");
        }

        Map<String, Object> stressResults = new HashMap<>();
        long totalStartTime = System.currentTimeMillis();

        try {
            AIService aiService = quotaCheckedAiService("ai-test-stress");
            log.info("开始AI服务压力测试，测试次数: {}", count);

            long totalResponseTime = 0;
            int successCount = 0;

            for (int i = 1; i <= count; i++) {
                try {
                    long startTime = System.currentTimeMillis();
                    String result = aiService.testChat("这是第" + i + "次压力测试");
                    long responseTime = System.currentTimeMillis() - startTime;
                    totalResponseTime += responseTime;
                    successCount++;

                    log.debug("压力测试 {}/{} 完成，响应时间: {}ms", i, count, responseTime);
                } catch (Exception e) {
                    log.warn("压力测试 {}/{} 失败", i, count, e);
                }
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;
            double avgResponseTime = successCount > 0 ? (double) totalResponseTime / successCount : 0;
            String maxQPS = successCount > 0 && avgResponseTime > 0
                    ? String.format("%.1f", 1000.0 / avgResponseTime)
                    : "0.0";

            stressResults.put("testCount", count);
            stressResults.put("successCount", successCount);
            stressResults.put("failureCount", count - successCount);
            stressResults.put("successRate", String.format("%.1f%%", (double) successCount / count * 100));
            stressResults.put("totalTime", totalTime + "ms");
            stressResults.put("avgResponseTime", String.format("%.1fms", avgResponseTime));
            stressResults.put("maxQPS", maxQPS);
            stressResults.put("timestamp", System.currentTimeMillis());

            log.info("AI服务压力测试完成 - 成功率: {}/{} ({:.1f%}), 平均响应时间: {:.1f}ms",
                    successCount, count, (double) successCount / count * 100, avgResponseTime);

            return Result.ok(stressResults);
        } catch (QuotaExceededSignal e) {
            return quotaFailure(e.getQuotaException());
        } catch (Exception e) {
            log.error("压力测试执行失败", e);
            return Result.fail("压力测试失败");
        }
    }

    /**
     * 获取测试统计信息
     * GET /api/ai/test/stats
     */
    @GetMapping("/stats")
    public Result getTestStats() {
        try {
            checkQuota("ai-test-stats");
            Map<String, Object> stats = new HashMap<>();
            stats.put("availableTests", Map.of(
                    "health", "AI服务健康检查",
                    "chat", "基础聊天测试",
                    "memory", "记忆功能测试",
                    "sentiment", "情感分析测试（仅测试用）",
                    "keywords", "关键词提取测试（仅测试用）",
                    "summarize", "文本总结测试（仅测试用）",
                    "batch", "批量功能测试",
                    "stress", "性能压力测试"
            ));
            stats.put("testEndpoints", Map.of(
                    "baseUrl", "/api/ai/test",
                    "healthCheck", "GET /api/ai/test/health",
                    "basicChat", "GET /api/ai/test/chat?message=你好",
                    "memoryTest", "GET /api/ai/test/memory?memoryId=test&message=测试",
                    "sentimentTest", "POST /api/ai/test/sentiment",
                    "keywordsTest", "POST /api/ai/test/keywords",
                    "summarizeTest", "POST /api/ai/test/summarize",
                    "batchTest", "GET /api/ai/test/batch",
                    "stressTest", "GET /api/ai/test/stress?count=5"
            ));
            stats.put("notes", Map.of(
                    "testingOnly", "情感分析、关键词提取、文本总结等功能仅在此控制器中用于测试",
                    "production", "生产环境请使用AnalysisService或ShopConsultantService",
                    "architecture", "AIService专注于测试，业务逻辑由专门的业务服务处理"
            ));
            stats.put("timestamp", System.currentTimeMillis());

            return Result.ok(stats);
        } catch (AiQuotaExceededException e) {
            return quotaFailure(e);
        } catch (Exception e) {
            log.error("获取测试统计失败", e);
            return Result.fail("获取统计失败");
        }
    }

    private AIService quotaCheckedAiService(String operation) {
        return (AIService) Proxy.newProxyInstance(
                AIService.class.getClassLoader(),
                new Class<?>[]{AIService.class},
                (proxy, method, args) -> {
                    try {
                        checkQuota(operation);
                    } catch (AiQuotaExceededException e) {
                        throw new QuotaExceededSignal(e);
                    }
                    try {
                        return method.invoke(aiService, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    protected void checkQuota(String operation) {
        aiUserQuotaService.checkAndConsume(StpUtil.getLoginIdAsString(), operation);
    }

    private Result quotaFailure(AiQuotaExceededException e) {
        return Result.fail(e.isInfraError() ? ErrorCode.SERVICE_UNAVAILABLE : ErrorCode.RATE_LIMITED, e.getMessage());
    }

    private static class QuotaExceededSignal extends Error {
        private final AiQuotaExceededException quotaException;

        private QuotaExceededSignal(AiQuotaExceededException quotaException) {
            super(quotaException);
            this.quotaException = quotaException;
        }

        private AiQuotaExceededException getQuotaException() {
            return quotaException;
        }
    }

    private int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private String safeKey(String value) {
        if (value == null || value.length() <= 16) {
            return value;
        }
        return value.substring(0, 8) + "***" + value.substring(value.length() - 4);
    }
}
