package com.hmdp.ai.retrieval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class DeterministicEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 12;

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            embeddings.add(embedText(segment == null ? "" : segment.text()));
        }
        return Response.from(embeddings);
    }

    private Embedding embedText(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        float[] values = new float[DIMENSIONS];

        values[0] = count(normalized, "退款", "退货", "售后", "赔付", "扣款");
        values[1] = count(normalized, "投诉", "举报", "客服", "商家", "处理");
        values[2] = count(normalized, "账号", "登录", "验证码", "密码", "安全");
        values[3] = count(normalized, "订单", "支付", "核销", "预约");
        values[4] = count(normalized, "服务", "店员", "态度", "加水");
        values[5] = count(normalized, "味道", "口味", "菜品", "牛肉", "招牌");
        values[6] = count(normalized, "环境", "卫生", "吵", "干净");
        values[7] = count(normalized, "价格", "人均", "性价比", "元", "套餐");
        values[8] = count(normalized, "排队", "分钟", "上菜", "等待");
        values[9] = count(normalized, "加微信", "返现", "广告", "http", "红包");
        values[10] = count(normalized, "旅行", "摄影", "天气", "咖啡");
        normalize(values);
        return Embedding.from(values);
    }

    private float count(String text, String... terms) {
        float score = 0.0f;
        for (String term : terms) {
            int index = text.indexOf(term.toLowerCase(Locale.ROOT));
            while (index >= 0) {
                if (!negatedAt(text, index)) {
                    score += 1.0f;
                }
                index = text.indexOf(term.toLowerCase(Locale.ROOT), index + term.length());
            }
        }
        return score;
    }

    private boolean negatedAt(String text, int index) {
        int sentenceStart = Math.max(
                Math.max(text.lastIndexOf('。', index), text.lastIndexOf('！', index)),
                Math.max(text.lastIndexOf('？', index), text.lastIndexOf('\n', index))) + 1;
        String sentencePrefix = text.substring(sentenceStart, index);
        return sentencePrefix.matches(".*(没有|没有任何|未包含|缺少|不含|不支持|不适合|无).*");
    }

    private void normalize(float[] values) {
        double sumSquares = 0.0;
        for (float value : values) {
            sumSquares += value * value;
        }
        if (sumSquares == 0.0) {
            values[values.length - 1] = 1.0f;
            return;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < values.length; i++) {
            values[i] = values[i] / norm;
        }
    }
}
