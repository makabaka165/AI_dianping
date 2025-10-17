package com.hmdp.service.impl;

import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class DocumentQualityAssessor {

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这"
    ));

    /**
     * 评估文档质量得分 (0-1)
     */
    public double assessQuality(Document document) {
        String content = document.text();
        
        if (content == null || content.trim().isEmpty()) {
            return 0.0;
        }

        // 计算各项指标
        double lengthScore = calculateLengthScore(content);
        double keywordScore = calculateKeywordScore(content);
        double structureScore = calculateStructureScore(content);
        double readabilityScore = calculateReadabilityScore(content);

        // 加权计算总分
        double totalScore = (lengthScore * 0.3) + (keywordScore * 0.3) + (structureScore * 0.2) + (readabilityScore * 0.2);
        
        log.debug("文档质量评估 - 长度得分: {}, 关键词得分: {}, 结构得分: {}, 可读性得分: {}, 总分: {}",
                lengthScore, keywordScore, structureScore, readabilityScore, totalScore);
        
        return Math.min(1.0, Math.max(0.0, totalScore));
    }

    /**
     * 计算长度得分
     */
    private double calculateLengthScore(String content) {
        int wordCount = content.length();
        if (wordCount < 50) {
            return 0.0; // 太短
        } else if (wordCount < 200) {
            return 0.5; // 较短
        } else if (wordCount < 1000) {
            return 1.0; // 适中
        } else if (wordCount < 5000) {
            return 0.8; // 较长
        } else {
            return 0.6; // 太长
        }
    }

    /**
     * 计算关键词得分
     */
    private double calculateKeywordScore(String content) {
        String[] words = content.split("[\\s\\p{Punct}]+");
        int validWordCount = 0;
        
        for (String word : words) {
            if (word.length() > 1 && !STOP_WORDS.contains(word)) {
                validWordCount++;
            }
        }
        
        // 假设平均每句话20个有效词为最佳
        double ratio = validWordCount / 20.0;
        return Math.min(1.0, ratio);
    }

    /**
     * 计算结构得分
     */
    private double calculateStructureScore(String content) {
        long paragraphCount = content.lines().filter(line -> !line.trim().isEmpty()).count();
        long sentenceCount = content.split("[。！？.!?]+").length;
        
        // 检查是否有标题、列表等结构化元素
        boolean hasStructure = content.contains("#") || content.contains("-") || content.contains("*");
        
        // 简单评估段落和句子的数量是否合理
        if (paragraphCount < 1 || sentenceCount < 1) {
            return 0.0;
        }
        
        // 假设每段2-10句为佳
        double avgSentencesPerParagraph = (double) sentenceCount / paragraphCount;
        double structureScore = 1.0 - Math.abs(avgSentencesPerParagraph - 6) / 10.0;
        
        // 如果有结构化元素，加分
        if (hasStructure) {
            structureScore += 0.2;
        }
        
        return Math.min(1.0, Math.max(0.0, structureScore));
    }

    /**
     * 计算可读性得分
     */
    private double calculateReadabilityScore(String content) {
        // 简单评估：检查是否包含过多的特殊字符或重复内容
        long specialCharCount = content.chars().filter(ch -> ch > 127).count();
        double specialCharRatio = (double) specialCharCount / content.length();
        
        // 特殊字符比例越低越好
        double readabilityScore = 1.0 - specialCharRatio;
        
        return Math.max(0.0, readabilityScore);
    }
}