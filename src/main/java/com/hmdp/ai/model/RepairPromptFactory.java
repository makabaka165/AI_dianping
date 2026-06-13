package com.hmdp.ai.model;

import org.springframework.stereotype.Component;

@Component
public class RepairPromptFactory {

    private static final int REASON_LIMIT = 120;

    public String repairPrompt(String originalPrompt, String qualityReason, String instruction) {
        return "上一次模型输出未通过质量校验，原因：" + safeReason(qualityReason) + "\n"
                + instruction + "\n"
                + "必须继续遵守原始数据边界：只能基于给定证据，不得编造店铺信息、价格、地址、评分。\n\n"
                + "原始任务：\n"
                + (originalPrompt == null ? "" : originalPrompt);
    }

    public String safeReason(String qualityReason) {
        if (qualityReason == null || qualityReason.trim().isEmpty()) {
            return "未给出具体原因";
        }
        String trimmed = qualityReason.trim();
        return trimmed.length() <= REASON_LIMIT ? trimmed : trimmed.substring(0, REASON_LIMIT);
    }
}
