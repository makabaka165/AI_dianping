package com.hmdp.ai.retrieval;

import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.infra.DocumentQualityProfile;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineQualityRetrievalEvaluationTest {

    private static final double LOW_QUALITY_THRESHOLD = 0.45;
    private static final int TOP_K = 3;

    private final DocumentQualityAssessor qualityAssessor = new DocumentQualityAssessor();
    private final DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();

    @Test
    void qualityAwareStrategiesShouldReduceLowQualityRetrievalPollution() {
        EvaluationSet evaluationSet = buildEvaluationSet();

        assertThat(evaluationSet.documents).hasSize(90);
        assertThat(evaluationSet.queries).hasSize(30);
        assertBalanced(evaluationSet.documents, DocumentQualityProfile.PLATFORM_POLICY);
        assertBalanced(evaluationSet.documents, DocumentQualityProfile.SHOP_REVIEW);

        List<ScoredDocument> scoredDocuments = scoreDocuments(evaluationSet.documents);
        QualityMetrics qualityMetrics = evaluateQualityClassification(scoredDocuments);
        Map<RetrievalStrategy, Metrics> metrics = new LinkedHashMap<>();
        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            metrics.put(strategy, evaluate(evaluationSet.queries, scoredDocuments, strategy));
        }

        Metrics allIndex = metrics.get(RetrievalStrategy.ALL_INDEX);
        Metrics skipLowQuality = metrics.get(RetrievalStrategy.SKIP_LOW_QUALITY);
        Metrics degradeLowQuality = metrics.get(RetrievalStrategy.DEGRADE_LOW_QUALITY);

        System.out.println("\nOffline quality retrieval evaluation");
        System.out.println("documents=" + evaluationSet.documents.size() + ", queries=" + evaluationSet.queries.size());
        System.out.println("quality -> " + qualityMetrics);
        metrics.forEach((strategy, value) -> System.out.println(strategy + " -> " + value));
        System.out.println("DEGRADE vs ALL lowQuality@3 reduction="
                + percentReduction(allIndex.lowQualityAtK, degradeLowQuality.lowQualityAtK));
        System.out.println("DEGRADE vs ALL precision@3 relative lift="
                + percentLift(allIndex.precisionAtK, degradeLowQuality.precisionAtK));
        System.out.println("DEGRADE vs ALL badPromotionRate reduction="
                + percentReduction(allIndex.badPromotionRate, degradeLowQuality.badPromotionRate));

        assertThat(allIndex.queryCount).isEqualTo(30);
        assertThat(qualityMetrics.lowQualityRecall).isGreaterThanOrEqualTo(0.95);
        assertThat(qualityMetrics.falseKillRate).isLessThanOrEqualTo(0.05);
        assertThat(allIndex.lowQualityAtK).isGreaterThan(0.20);
        assertThat(degradeLowQuality.lowQualityAtK).isLessThan(allIndex.lowQualityAtK * 0.50);
        assertThat(degradeLowQuality.precisionAtK).isGreaterThan(allIndex.precisionAtK);
        assertThat(degradeLowQuality.badPromotionRate).isLessThan(allIndex.badPromotionRate * 0.50);
        assertThat(degradeLowQuality.recallAtK).isGreaterThanOrEqualTo(allIndex.recallAtK - 0.05);

        assertThat(skipLowQuality.lowQualityAtK).isLessThan(allIndex.lowQualityAtK * 0.25);
        assertThat(skipLowQuality.precisionAtK).isGreaterThanOrEqualTo(degradeLowQuality.precisionAtK);
        assertThat(skipLowQuality.recallAtK).isGreaterThanOrEqualTo(allIndex.recallAtK - 0.10);
    }

    private List<ScoredDocument> scoreDocuments(List<EvalDocument> documents) {
        return documents.stream()
                .map(document -> {
                    DocumentQualityAssessment assessment = qualityAssessor.assess(document.content, document.profile);
                    return new ScoredDocument(document, assessment, embedding(document.content));
                })
                .collect(Collectors.toList());
    }

    private QualityMetrics evaluateQualityClassification(List<ScoredDocument> scoredDocuments) {
        int goldPoor = 0;
        int detectedPoor = 0;
        int usable = 0;
        int falseKilled = 0;

        for (ScoredDocument document : scoredDocuments) {
            boolean systemLowQuality = document.assessment.getScore() < LOW_QUALITY_THRESHOLD;
            if (document.document.goldQuality == GoldQuality.POOR) {
                goldPoor++;
                if (systemLowQuality) {
                    detectedPoor++;
                }
            } else {
                usable++;
                if (systemLowQuality) {
                    falseKilled++;
                }
            }
        }

        return new QualityMetrics(
                ratio(detectedPoor, goldPoor),
                ratio(falseKilled, usable),
                detectedPoor,
                goldPoor,
                falseKilled,
                usable);
    }

    private Metrics evaluate(List<EvalQuery> queries, List<ScoredDocument> scoredDocuments, RetrievalStrategy strategy) {
        int resultSlots = 0;
        int relevantUsableHits = 0;
        int queriesWithRelevantHit = 0;
        int idealTopHits = 0;
        int lowQualityHits = 0;
        int badTopHits = 0;

        for (EvalQuery query : queries) {
            List<RankedDocument> top = rank(query, scoredDocuments, strategy);
            resultSlots += top.size();
            if (!top.isEmpty() && top.get(0).document.goldQuality == GoldQuality.POOR) {
                badTopHits++;
            }
            if (!top.isEmpty() && query.idealTopDocumentIds.contains(top.get(0).document.id)) {
                idealTopHits++;
            }
            boolean hasRelevant = false;
            for (RankedDocument ranked : top) {
                boolean relevant = query.relevantDocumentIds.contains(ranked.document.id);
                boolean usable = ranked.document.goldQuality != GoldQuality.POOR;
                if (relevant && usable) {
                    relevantUsableHits++;
                    hasRelevant = true;
                }
                if (ranked.document.goldQuality == GoldQuality.POOR) {
                    lowQualityHits++;
                }
            }
            if (hasRelevant) {
                queriesWithRelevantHit++;
            }
        }

        return new Metrics(
                queries.size(),
                ratio(relevantUsableHits, resultSlots),
                ratio(queriesWithRelevantHit, queries.size()),
                ratio(lowQualityHits, resultSlots),
                ratio(badTopHits, queries.size()),
                ratio(idealTopHits, queries.size()));
    }

    private List<RankedDocument> rank(EvalQuery query, List<ScoredDocument> documents, RetrievalStrategy strategy) {
        Embedding queryEmbedding = embedding(query.text + " " + String.join(" ", query.terms));
        return documents.stream()
                .filter(document -> document.document.profile == query.profile)
                .map(document -> new RankedDocument(document.document, adjustedScore(queryEmbedding, document, strategy)))
                .filter(document -> document.score > 0.0)
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .limit(TOP_K)
                .collect(Collectors.toList());
    }

    private double adjustedScore(Embedding queryEmbedding, ScoredDocument document, RetrievalStrategy strategy) {
        boolean systemLowQuality = document.assessment.getScore() < LOW_QUALITY_THRESHOLD;
        if (strategy == RetrievalStrategy.SKIP_LOW_QUALITY && systemLowQuality) {
            return -1.0;
        }
        double score = cosine(queryEmbedding.vector(), document.embedding.vector());
        if (strategy == RetrievalStrategy.DEGRADE_LOW_QUALITY && systemLowQuality) {
            return score * 0.35;
        }
        return score;
    }

    private Embedding embedding(String text) {
        return embeddingModel.embed(text).content();
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private EvaluationSet buildEvaluationSet() {
        List<EvalDocument> documents = new ArrayList<>();
        documents.addAll(policyDocuments());
        documents.addAll(reviewDocuments());

        List<EvalQuery> queries = new ArrayList<>();
        queries.addAll(policyQueries());
        queries.addAll(reviewQueries());

        return new EvaluationSet(documents, queries);
    }

    private List<EvalDocument> policyDocuments() {
        List<EvalDocument> docs = new ArrayList<>();
        addPolicyGroup(docs, "refund", "退款", Arrays.asList("退款", "退货", "售后", "赔付", "扣款"),
                "重复扣款、商家未履约、商品或服务与页面描述不符时，用户可以在订单详情提交退款申请。",
                "平台客服会在 24 小时内审核订单号、支付截图、商家沟通记录和现场凭证。",
                "已核销订单、超过 7 天未反馈或缺少有效凭证的申请，平台可能无法支持全额退款。");
        addPolicyGroup(docs, "complaint", "投诉", Arrays.asList("投诉", "举报", "客服", "商家", "处理"),
                "用户发现商家拒绝履约、虚假宣传或服务态度恶劣时，可以通过订单页投诉商家。",
                "平台客服会收集双方证据，必要时联系商家说明情况，并在 3 个工作日内反馈处理结果。",
                "恶意投诉、重复提交相同材料或缺少订单依据的请求，平台会要求补充材料。");
        addPolicyGroup(docs, "account", "账号登录", Arrays.asList("账号", "登录", "验证码", "密码", "安全"),
                "用户无法登录账号时，可以检查手机号、验证码、密码和网络状态。",
                "账号提示安全风险时，用户需要向平台客服提交手机号、最近登录时间和身份凭证。",
                "疑似盗用、异常下单或频繁验证失败的账号，平台可能临时限制登录能力。");
        addPolicyGroup(docs, "payment", "支付订单", Arrays.asList("订单", "支付", "扣款", "核销", "预约"),
                "订单支付成功但页面未更新时，用户可以保留支付截图并在订单详情联系平台客服。",
                "平台会核对支付流水、订单状态、核销记录和商家预约情况，确认后修复订单状态。",
                "第三方支付通道延迟、用户重复刷新或预约时间冲突，可能导致处理时效延长。");
        addPolicyGroup(docs, "coupon", "优惠券", Arrays.asList("优惠券", "订单", "支付", "核销", "客服"),
                "优惠券无法使用时，用户需要确认有效期、适用门店、最低消费和订单类型。",
                "平台客服会检查券状态、领取记录、核销规则和支付前展示信息。",
                "过期券、已核销券、非活动门店或不满足门槛的订单，平台不支持补偿。");
        return docs;
    }

    private void addPolicyGroup(List<EvalDocument> docs,
                                String topic,
                                String title,
                                List<String> terms,
                                String goodScope,
                                String goodProcess,
                                String goodLimit) {
        for (int i = 1; i <= 3; i++) {
            docs.add(new EvalDocument(
                    "policy_" + topic + "_good_" + i,
                    DocumentQualityProfile.PLATFORM_POLICY,
                    GoldQuality.GOOD,
                    title + "完整规则 " + i,
                    "# " + title + "完整规则 " + i + "\n\n"
                            + "适用范围：" + goodScope + "\n\n"
                            + "处理流程：" + goodProcess + "\n\n"
                            + "责任边界：商家原因导致的问题由商家承担，用户个人原因取消订单需遵守页面公示规则。\n\n"
                            + "限制条件：" + goodLimit + "\n\n"
                            + "客服渠道：用户可以通过订单详情页、平台客服入口或安全中心提交材料。"));
        }
        for (int i = 1; i <= 3; i++) {
            docs.add(new EvalDocument(
                    "policy_" + topic + "_fair_" + i,
                    DocumentQualityProfile.PLATFORM_POLICY,
                    GoldQuality.FAIR,
                    title + "简要说明 " + i,
                    "# " + title + "简要说明 " + i + "\n\n"
                            + "用户遇到" + title + "问题时，可以先查看订单状态并联系平台客服。"
                            + "平台会根据规则进行处理，但文档只说明基本方向，没有完整列出时效、凭证、责任边界和限制条件。"
                            + "相关关键词包括：" + String.join("、", terms.subList(0, Math.min(3, terms.size()))) + "。"));
        }
        for (int i = 1; i <= 3; i++) {
            docs.add(new EvalDocument(
                    "policy_" + topic + "_poor_" + i,
                    DocumentQualityProfile.PLATFORM_POLICY,
                    GoldQuality.POOR,
                    title + "低质片段 " + i,
                    poorPolicyText(title, terms, i)));
        }
    }

    private String poorPolicyText(String title, List<String> terms, int index) {
        if (index == 1) {
            return "# 城市生活随笔\n\n今天沿着河边散步，天气很好，街边咖啡店音乐不错。"
                    + "文章只是个人感受，没有任何" + String.join("、", terms.subList(0, Math.min(4, terms.size())))
                    + "处理流程或平台规则说明。";
        }
        if (index == 2) {
            return title + " " + title + " @@@ ### ￥￥￥ 加微信返现 http://promo.example.com 复制领取红包";
        }
        return title + "很好，整体可以，下次再说。";
    }

    private List<EvalDocument> reviewDocuments() {
        List<EvalDocument> docs = new ArrayList<>();
        addReviewGroup(docs, "service", "服务", Arrays.asList("服务", "店员", "态度", "加水"),
                "服务态度不错，店员会主动加水，也会提醒套餐里哪些菜比较辣。",
                "周末和朋友来吃晚餐，排队等了 20 分钟，人均 88 元。");
        addReviewGroup(docs, "taste", "味道", Arrays.asList("味道", "口味", "菜品", "牛肉", "招牌"),
                "招牌菜味道稳定，牛肉分量足，辣度说明清楚，整体口味适合朋友聚餐。",
                "午餐点了双人套餐，上菜大约 15 分钟，人均 76 元。");
        addReviewGroup(docs, "environment", "环境", Arrays.asList("环境", "卫生", "干净", "吵"),
                "店里环境干净，桌面和餐具卫生情况不错，只是晚高峰大厅有点吵。",
                "晚上和同事聚餐，提前预约后到店不用等位。");
        addReviewGroup(docs, "price", "价格", Arrays.asList("价格", "人均", "性价比", "元", "套餐"),
                "套餐价格透明，人均 62 元，菜量够两个人吃，性价比不错。",
                "工作日午餐来得比较早，上菜速度稳定。");
        addReviewGroup(docs, "queue", "排队", Arrays.asList("排队", "分钟", "上菜", "等待"),
                "周末排队等了 25 分钟，上菜大约 15 分钟，服务员会及时说明等待时间。",
                "和朋友临时过来吃晚餐，店里人很多但安排比较有序。");
        return docs;
    }

    private void addReviewGroup(List<EvalDocument> docs,
                                String topic,
                                String title,
                                List<String> terms,
                                String goodEvidence,
                                String goodScenario) {
        for (int i = 1; i <= 3; i++) {
            docs.add(new EvalDocument(
                    "review_" + topic + "_good_" + i,
                    DocumentQualityProfile.SHOP_REVIEW,
                    GoldQuality.GOOD,
                    title + "高质量评价 " + i,
                    goodScenario + goodEvidence + "环境、价格和卫生都有具体体验，适合给其他用户做参考。"));
        }
        for (int i = 1; i <= 3; i++) {
            docs.add(new EvalDocument(
                    "review_" + topic + "_fair_" + i,
                    DocumentQualityProfile.SHOP_REVIEW,
                    GoldQuality.FAIR,
                    title + "中等评价 " + i,
                    title + "还可以，" + terms.get(0) + "方面有印象，人均大概 60 元。"
                            + "内容有一点参考价值，但场景和细节不够完整。"));
        }
        for (int i = 1; i <= 3; i++) {
            docs.add(new EvalDocument(
                    "review_" + topic + "_poor_" + i,
                    DocumentQualityProfile.SHOP_REVIEW,
                    GoldQuality.POOR,
                    title + "低质评价 " + i,
                    poorReviewText(title, terms, i)));
        }
    }

    private String poorReviewText(String title, List<String> terms, int index) {
        if (index == 1) {
            return "挺好的，还不错，整体可以，下次再说。";
        }
        if (index == 2) {
            return "加微信返现，扫码领优惠代理，http://promo.example.com，复制这条评价有红包。";
        }
        return title + " " + terms.get(0) + " @@@ ### 复制复制复制 111 222";
    }

    private List<EvalQuery> policyQueries() {
        return Arrays.asList(
                policyQuery("平台重复扣款后怎么申请退款？", "refund", "退款", "扣款", "支付"),
                policyQuery("商家不履约应该怎么投诉？", "complaint", "投诉", "商家", "客服"),
                policyQuery("账号登录收不到验证码怎么办？", "account", "账号", "登录", "验证码"),
                policyQuery("支付成功但订单没有更新怎么办？", "payment", "支付", "订单", "扣款"),
                policyQuery("优惠券为什么不能核销？", "coupon", "优惠券", "核销", "订单"),
                policyQuery("退款申请需要哪些凭证？", "refund", "退款", "凭证", "售后"),
                policyQuery("举报商家虚假宣传走什么流程？", "complaint", "举报", "商家", "处理"),
                policyQuery("账号提示安全风险怎么恢复？", "account", "账号", "安全", "密码"),
                policyQuery("预约订单支付后无法使用怎么办？", "payment", "预约", "订单", "支付"),
                policyQuery("优惠券过期了还能补偿吗？", "coupon", "优惠券", "客服", "支付"),
                policyQuery("商家原因取消订单责任怎么算？", "refund", "商家", "订单", "退款"),
                policyQuery("投诉处理多久会反馈？", "complaint", "投诉", "客服", "处理"),
                policyQuery("频繁验证失败会限制登录吗？", "account", "登录", "安全", "验证码"),
                policyQuery("订单核销记录异常怎么处理？", "payment", "核销", "订单", "客服"),
                policyQuery("优惠券适用门店怎么确认？", "coupon", "优惠券", "订单", "核销"));
    }

    private EvalQuery policyQuery(String text, String topic, String... terms) {
        return new EvalQuery(
                text,
                DocumentQualityProfile.PLATFORM_POLICY,
                Arrays.asList(terms),
                ids("policy_" + topic + "_good_", 3, "policy_" + topic + "_fair_", 3),
                ids("policy_" + topic + "_good_", 3));
    }

    private List<EvalQuery> reviewQueries() {
        return Arrays.asList(
                reviewQuery("这家店服务态度怎么样？", "service", "服务", "态度", "店员"),
                reviewQuery("招牌菜味道和口味稳定吗？", "taste", "味道", "口味", "招牌"),
                reviewQuery("店内环境和卫生情况如何？", "environment", "环境", "卫生", "干净"),
                reviewQuery("人均价格和性价比怎么样？", "price", "价格", "人均", "性价比"),
                reviewQuery("周末排队和上菜速度如何？", "queue", "排队", "上菜", "分钟"),
                reviewQuery("服务员会不会主动加水提醒？", "service", "服务", "加水", "店员"),
                reviewQuery("牛肉分量和菜品味道怎么样？", "taste", "牛肉", "菜品", "味道"),
                reviewQuery("大厅会不会很吵，适合聚餐吗？", "environment", "环境", "吵", "聚餐"),
                reviewQuery("套餐价格透明吗？", "price", "套餐", "价格", "元"),
                reviewQuery("等待时间会不会太久？", "queue", "等待", "排队", "分钟"),
                reviewQuery("朋友聚餐时服务体验如何？", "service", "服务", "朋友", "聚餐"),
                reviewQuery("午餐双人套餐口味如何？", "taste", "午餐", "套餐", "味道"),
                reviewQuery("餐具和桌面卫生好吗？", "environment", "卫生", "干净", "环境"),
                reviewQuery("两个人吃人均大概多少？", "price", "人均", "元", "价格"),
                reviewQuery("预约后到店还需要等位吗？", "queue", "预约", "等待", "排队"));
    }

    private EvalQuery reviewQuery(String text, String topic, String... terms) {
        return new EvalQuery(
                text,
                DocumentQualityProfile.SHOP_REVIEW,
                Arrays.asList(terms),
                ids("review_" + topic + "_good_", 3, "review_" + topic + "_fair_", 3),
                ids("review_" + topic + "_good_", 3));
    }

    private List<String> ids(String goodPrefix, int goodCount) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= goodCount; i++) {
            ids.add(goodPrefix + i);
        }
        return ids;
    }

    private List<String> ids(String goodPrefix, int goodCount, String fairPrefix, int fairCount) {
        List<String> ids = ids(goodPrefix, goodCount);
        for (int i = 1; i <= fairCount; i++) {
            ids.add(fairPrefix + i);
        }
        return ids;
    }

    private void assertBalanced(List<EvalDocument> documents, DocumentQualityProfile profile) {
        Map<GoldQuality, Long> counts = documents.stream()
                .filter(document -> document.profile == profile)
                .collect(Collectors.groupingBy(document -> document.goldQuality, Collectors.counting()));
        assertThat(counts.get(GoldQuality.GOOD)).isEqualTo(15);
        assertThat(counts.get(GoldQuality.FAIR)).isEqualTo(15);
        assertThat(counts.get(GoldQuality.POOR)).isEqualTo(15);
    }

    private double ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return round((double) numerator / denominator);
    }

    private String percentReduction(double before, double after) {
        return formatPercent(before == 0.0 ? 0.0 : (before - after) / before);
    }

    private String percentLift(double before, double after) {
        return formatPercent(before == 0.0 ? 0.0 : (after - before) / before);
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private enum RetrievalStrategy {
        ALL_INDEX,
        SKIP_LOW_QUALITY,
        DEGRADE_LOW_QUALITY
    }

    private enum GoldQuality {
        GOOD,
        FAIR,
        POOR
    }

    private static class EvaluationSet {
        private final List<EvalDocument> documents;
        private final List<EvalQuery> queries;

        EvaluationSet(List<EvalDocument> documents, List<EvalQuery> queries) {
            this.documents = documents;
            this.queries = queries;
        }
    }

    private static class EvalDocument {
        private final String id;
        private final DocumentQualityProfile profile;
        private final GoldQuality goldQuality;
        private final String title;
        private final String content;

        EvalDocument(String id,
                     DocumentQualityProfile profile,
                     GoldQuality goldQuality,
                     String title,
                     String content) {
            this.id = id;
            this.profile = profile;
            this.goldQuality = goldQuality;
            this.title = title;
            this.content = content;
        }
    }

    private static class EvalQuery {
        private final String text;
        private final DocumentQualityProfile profile;
        private final List<String> terms;
        private final Set<String> relevantDocumentIds;
        private final Set<String> idealTopDocumentIds;

        EvalQuery(String text,
                  DocumentQualityProfile profile,
                  List<String> terms,
                  List<String> relevantDocumentIds,
                  List<String> idealTopDocumentIds) {
            this.text = text;
            this.profile = profile;
            this.terms = terms;
            this.relevantDocumentIds = new LinkedHashSet<>(relevantDocumentIds);
            this.idealTopDocumentIds = new LinkedHashSet<>(idealTopDocumentIds);
        }
    }

    private static class ScoredDocument {
        private final EvalDocument document;
        private final DocumentQualityAssessment assessment;
        private final Embedding embedding;

        ScoredDocument(EvalDocument document, DocumentQualityAssessment assessment, Embedding embedding) {
            this.document = document;
            this.assessment = assessment;
            this.embedding = embedding;
        }
    }

    private static class RankedDocument {
        private final EvalDocument document;
        private final double score;

        RankedDocument(EvalDocument document, double score) {
            this.document = document;
            this.score = score;
        }
    }

    private static class Metrics {
        private final int queryCount;
        private final double precisionAtK;
        private final double recallAtK;
        private final double lowQualityAtK;
        private final double badPromotionRate;
        private final double idealTop1Rate;

        Metrics(int queryCount,
                double precisionAtK,
                double recallAtK,
                double lowQualityAtK,
                double badPromotionRate,
                double idealTop1Rate) {
            this.queryCount = queryCount;
            this.precisionAtK = precisionAtK;
            this.recallAtK = recallAtK;
            this.lowQualityAtK = lowQualityAtK;
            this.badPromotionRate = badPromotionRate;
            this.idealTop1Rate = idealTop1Rate;
        }

        @Override
        public String toString() {
            return "queryCount=" + queryCount
                    + ", precision@3=" + precisionAtK
                    + ", recall@3=" + recallAtK
                    + ", lowQuality@3=" + lowQualityAtK
                    + ", badPromotionRate=" + badPromotionRate
                    + ", idealTop1Rate=" + idealTop1Rate;
        }
    }

    private static class QualityMetrics {
        private final double lowQualityRecall;
        private final double falseKillRate;
        private final int detectedLowQuality;
        private final int goldLowQuality;
        private final int falseKilled;
        private final int usableDocuments;

        QualityMetrics(double lowQualityRecall,
                       double falseKillRate,
                       int detectedLowQuality,
                       int goldLowQuality,
                       int falseKilled,
                       int usableDocuments) {
            this.lowQualityRecall = lowQualityRecall;
            this.falseKillRate = falseKillRate;
            this.detectedLowQuality = detectedLowQuality;
            this.goldLowQuality = goldLowQuality;
            this.falseKilled = falseKilled;
            this.usableDocuments = usableDocuments;
        }

        @Override
        public String toString() {
            return "lowQualityRecall=" + lowQualityRecall
                    + " (" + detectedLowQuality + "/" + goldLowQuality + ")"
                    + ", falseKillRate=" + falseKillRate
                    + " (" + falseKilled + "/" + usableDocuments + ")";
        }
    }
}
