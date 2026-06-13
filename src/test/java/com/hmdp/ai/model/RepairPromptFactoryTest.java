package com.hmdp.ai.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepairPromptFactoryTest {

    private final RepairPromptFactory factory = new RepairPromptFactory();

    @Test
    void shouldTruncateLongQualityReason() {
        String reason = "x".repeat(200);

        assertThat(factory.safeReason(reason)).hasSize(120);
    }

    @Test
    void repairPromptShouldKeepEvidenceBoundaryRule() {
        String prompt = factory.repairPrompt("原始任务", "格式错误", "重新输出 JSON");

        assertThat(prompt)
                .contains("只能基于给定证据")
                .contains("不得编造")
                .contains("原始任务");
    }
}
