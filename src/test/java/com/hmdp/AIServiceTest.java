package com.hmdp;

import com.hmdp.service.ai.AIService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@Tag("external")
@SpringBootTest
public class AIServiceTest {

    @Autowired
    private AIService aiService;

    @Test
    public void testChat() {
        String response = aiService.testChat("你好，请介绍一下自己");
        System.out.println("AI回复: " + response);
        assertNotNull(response);
        assertFalse(response.isEmpty());
    }

    @Test
    public void testAnalyzeReviewSentiment() {
        // 正面评论测试
        String positiveReview = "这家餐厅的菜品非常棒，服务态度也很好，强烈推荐！";
        String sentiment1 = aiService.analyzeSentiment(positiveReview);
        System.out.println("正面评论分析结果: " + sentiment1);
        assertNotNull(sentiment1);

        // 负面评论测试
        String negativeReview = "菜品一般，服务态度差，不推荐";
        String sentiment2 = aiService.analyzeSentiment(negativeReview);
        System.out.println("负面评论分析结果: " + sentiment2);
        assertNotNull(sentiment2);

        // 中性评论测试
        String neutralReview = "菜品还可以，价格合理";
        String sentiment3 = aiService.analyzeSentiment(neutralReview);
        System.out.println("中性评论分析结果: " + sentiment3);
        assertNotNull(sentiment3);
    }

    @Test
    public void testGenerateShopRecommendation() {
        String recommendation = aiService.testChat(
                "老王家小面"+
                "面食店"+
                "手工拉面，汤鲜味美，价格实惠"
        );
        System.out.println("生成的推荐语: " + recommendation);
        assertNotNull(recommendation);
        assertFalse(recommendation.isEmpty());
        assertFalse(recommendation.equals("推荐语生成失败"));
    }
}
