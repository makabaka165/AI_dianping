package com.hmdp.ai.infra;

import lombok.Builder;
import lombok.Value;
import lombok.Singular;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class DocumentQualityAssessment {
    DocumentQualityProfile profile;
    DocumentQualityLevel level;
    double score;
    int charCount;
    int chineseCharCount;
    int englishWordCount;
    int paragraphCount;
    int sentenceCount;
    int noiseCharCount;
    double noiseRatio;
    @Singular
    Map<String, Double> dimensionScores;
    @Singular
    List<String> keywords;
    @Singular
    List<String> issues;
    @Singular
    List<String> suggestions;
}
