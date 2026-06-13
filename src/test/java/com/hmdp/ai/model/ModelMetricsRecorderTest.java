package com.hmdp.ai.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMetricsRecorderTest {

    private final ModelMetricsRecorder recorder = new ModelMetricsRecorder();

    @Test
    void analysisTypeShouldKeepExistingRules() {
        assertThat(recorder.analysisType("ask:analyzeShopData")).isEqualTo("ask");
        assertThat(recorder.analysisType("classifyIntent")).isEqualTo("intent");
        assertThat(recorder.analysisType("freeChat")).isEqualTo("chat");
        assertThat(recorder.analysisType("generateStructuredAnalysis")).isEqualTo("summary");
        assertThat(recorder.analysisType("other")).isEqualTo("other");
    }
}
