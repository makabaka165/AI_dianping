package com.hmdp.ai.infra;

import com.hmdp.dto.ai.ReviewDoc;
import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DocumentQualityAssessor {

    private static final Pattern CHINESE_CHAR = Pattern.compile("[\\u4E00-\\u9FFF]");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern SENTENCE_SPLITTER = Pattern.compile("[。！？!?；;\\n]+");
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+");

    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到",
            "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "可以", "进行", "相关", "如果", "以及",
            "通过", "根据", "需要", "用户"
    ));

    private static final List<String> POLICY_TOPICS = Arrays.asList(
            "平台", "退款", "退货", "投诉", "举报", "账号", "登录", "订单", "支付", "商家", "客服", "优惠券", "核销", "赔付", "安全", "售后"
    );

    private static final List<String> POLICY_COMPLETENESS_TERMS = Arrays.asList(
            "适用范围", "条件", "流程", "步骤", "时效", "时间", "审核", "凭证", "责任", "限制", "渠道", "处理", "结果", "规则", "说明"
    );

    private static final List<String> REVIEW_ASPECTS = Arrays.asList(
            "服务", "环境", "口味", "味道", "菜品", "价格", "人均", "性价比", "位置", "排队", "卫生", "态度", "体验", "分量", "套餐", "停车"
    );

    private static final List<String> REVIEW_NOISE_TERMS = Arrays.asList(
            "加微信", "返现", "刷单", "复制", "无意义", "广告", "http", "www.", "优惠代理"
    );

    public DocumentQualityAssessment assess(Document document) {
        return assess(document, DocumentQualityProfile.GENERAL);
    }

    public DocumentQualityAssessment assess(Document document, DocumentQualityProfile profile) {
        String content = document == null ? null : document.text();
        return assess(content, profile == null ? DocumentQualityProfile.GENERAL : profile);
    }

    public DocumentQualityAssessment assessReview(ReviewDoc review) {
        if (review == null) {
            return assess("", DocumentQualityProfile.SHOP_REVIEW);
        }
        StringBuilder content = new StringBuilder();
        if (!blank(review.getTitle())) {
            content.append(review.getTitle()).append('\n');
        }
        if (!blank(review.getContent())) {
            content.append(review.getContent());
        }
        return assess(content.toString(), DocumentQualityProfile.SHOP_REVIEW);
    }

    public DocumentQualityAssessment assess(String content, DocumentQualityProfile profile) {
        DocumentQualityProfile safeProfile = profile == null ? DocumentQualityProfile.GENERAL : profile;
        TextStats stats = analyze(content);
        if (stats.blank) {
            return buildAssessment(safeProfile, stats, 0.0, new LinkedHashMap<>(),
                    new ArrayList<>(), list("EMPTY_CONTENT"), list("补充文档正文后再导入知识库"));
        }

        ProfileScore profileScore;
        if (safeProfile == DocumentQualityProfile.PLATFORM_POLICY) {
            profileScore = scorePlatformPolicy(stats);
        } else if (safeProfile == DocumentQualityProfile.SHOP_REVIEW) {
            profileScore = scoreShopReview(stats);
        } else {
            profileScore = scoreGeneral(stats);
        }

        DocumentQualityAssessment assessment = buildAssessment(safeProfile, stats, profileScore.totalScore,
                profileScore.dimensionScores, profileScore.keywords, profileScore.issues, profileScore.suggestions);
        log.debug("Document quality assessed profile={}, score={}, level={}, dimensions={}, issues={}",
                assessment.getProfile(), assessment.getScore(), assessment.getLevel(),
                assessment.getDimensionScores(), assessment.getIssues());
        return assessment;
    }

    private ProfileScore scoreGeneral(TextStats stats) {
        double length = lengthScore(stats.charCount, 120, 300, 2500, 8000);
        double keyword = keywordDiversityScore(stats);
        double structure = structureScore(stats);
        double readability = readabilityScore(stats);
        Map<String, Double> dimensions = dimensions(
                "length", length,
                "keywordDiversity", keyword,
                "structure", structure,
                "readability", readability
        );
        double total = weighted(dimensions,
                "length", 0.25,
                "keywordDiversity", 0.25,
                "structure", 0.25,
                "readability", 0.25
        );
        List<String> issues = commonIssues(stats);
        List<String> suggestions = commonSuggestions(issues);
        return new ProfileScore(total, dimensions, extractKeywords(stats, 10), issues, suggestions);
    }

    private ProfileScore scorePlatformPolicy(TextStats stats) {
        double topic = coverageScore(stats.content, POLICY_TOPICS, 4, true);
        double completeness = coverageScore(stats.content, POLICY_COMPLETENESS_TERMS, 6, true);
        double structure = structureScore(stats);
        double retrievability = retrievabilityScore(stats, POLICY_TOPICS, POLICY_COMPLETENESS_TERMS);
        double readability = readabilityScore(stats);
        double length = lengthScore(stats.charCount, 180, 350, 3500, 10000);

        Map<String, Double> dimensions = dimensions(
                "topicRelevance", topic,
                "policyCompleteness", completeness,
                "structure", structure,
                "retrievability", retrievability,
                "readability", readability,
                "length", length
        );
        double total = weighted(dimensions,
                "topicRelevance", 0.20,
                "policyCompleteness", 0.25,
                "structure", 0.18,
                "retrievability", 0.17,
                "readability", 0.12,
                "length", 0.08
        );
        List<String> issues = commonIssues(stats);
        if (topic < 0.45) {
            issues.add("LOW_PLATFORM_POLICY_RELEVANCE");
        }
        if (completeness < 0.50) {
            issues.add("MISSING_POLICY_PROCESS_OR_BOUNDARIES");
        }
        if (retrievability < 0.45) {
            issues.add("LOW_RETRIEVABILITY");
        }
        List<String> suggestions = commonSuggestions(issues);
        if (issues.contains("LOW_PLATFORM_POLICY_RELEVANCE")) {
            suggestions.add("补充退款、投诉、账号、订单、支付、商家等平台规则主题词");
        }
        if (issues.contains("MISSING_POLICY_PROCESS_OR_BOUNDARIES")) {
            suggestions.add("补充适用范围、处理流程、凭证要求、责任划分、时效和限制条件");
        }
        if (issues.contains("LOW_RETRIEVABILITY")) {
            suggestions.add("按问答、步骤或小节组织内容，并保留用户可能检索的关键词");
        }
        return new ProfileScore(total, dimensions, mergeKeywords(stats, POLICY_TOPICS, POLICY_COMPLETENESS_TERMS), issues, suggestions);
    }

    private ProfileScore scoreShopReview(TextStats stats) {
        double evidence = reviewEvidenceScore(stats);
        double aspect = coverageScore(stats.content, REVIEW_ASPECTS, 3);
        double sentiment = sentimentSignalScore(stats.content);
        double retrievability = retrievabilityScore(stats, REVIEW_ASPECTS, Arrays.asList("推荐", "不错", "一般", "差", "满意", "失望"));
        double readability = readabilityScore(stats);
        double spamSafety = spamSafetyScore(stats);
        double length = lengthScore(stats.charCount, 20, 60, 500, 1200);

        Map<String, Double> dimensions = dimensions(
                "reviewEvidence", evidence,
                "aspectCoverage", aspect,
                "sentimentSignal", sentiment,
                "retrievability", retrievability,
                "readability", readability,
                "spamSafety", spamSafety,
                "length", length
        );
        double total = weighted(dimensions,
                "reviewEvidence", 0.20,
                "aspectCoverage", 0.18,
                "sentimentSignal", 0.14,
                "retrievability", 0.16,
                "readability", 0.12,
                "spamSafety", 0.12,
                "length", 0.08
        );
        List<String> issues = commonIssues(stats);
        if (aspect < 0.35) {
            issues.add("LOW_REVIEW_ASPECT_COVERAGE");
        }
        if (evidence < 0.45) {
            issues.add("LOW_REVIEW_EVIDENCE_VALUE");
        }
        if (spamSafety < 0.75) {
            issues.add("POSSIBLE_REVIEW_SPAM_OR_AD");
        }
        List<String> suggestions = commonSuggestions(issues);
        if (issues.contains("LOW_REVIEW_ASPECT_COVERAGE")) {
            suggestions.add("评价最好包含服务、环境、口味、价格、位置等可检索维度");
        }
        if (issues.contains("LOW_REVIEW_EVIDENCE_VALUE")) {
            suggestions.add("补充具体体验、消费场景或可引用细节，避免只有情绪化短句");
        }
        if (issues.contains("POSSIBLE_REVIEW_SPAM_OR_AD")) {
            suggestions.add("清理广告、返现、外链或重复灌水内容");
        }
        return new ProfileScore(total, dimensions, mergeKeywords(stats, REVIEW_ASPECTS, Arrays.asList("推荐", "满意", "失望", "排队")), issues, suggestions);
    }

    private TextStats analyze(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        TextStats stats = new TextStats();
        stats.content = content;
        stats.blank = content.isEmpty();
        stats.charCount = content.length();
        stats.chineseCharCount = countMatches(CHINESE_CHAR, content);
        stats.englishWordCount = countMatches(ENGLISH_WORD, content);
        stats.noiseCharCount = countNoiseCharacters(content);
        stats.noiseRatio = stats.charCount == 0 ? 0.0 : (double) stats.noiseCharCount / stats.charCount;
        stats.paragraphCount = (int) content.lines().filter(line -> !line.trim().isEmpty()).count();
        stats.sentenceCount = countSentences(content);
        stats.tokens = tokenize(content);
        stats.uniqueTokens = new LinkedHashSet<>(stats.tokens);
        stats.structuredLineCount = countStructuredLines(content);
        stats.duplicateLineRatio = duplicateLineRatio(content);
        return stats;
    }

    private DocumentQualityAssessment buildAssessment(DocumentQualityProfile profile,
                                                      TextStats stats,
                                                      double score,
                                                      Map<String, Double> dimensions,
                                                      List<String> keywords,
                                                      List<String> issues,
                                                      List<String> suggestions) {
        double safeScore = clamp(score);
        if (stats.charCount < 20) {
            safeScore = Math.min(safeScore, 0.44);
        }
        if (stats.noiseRatio > 0.15) {
            safeScore = Math.min(safeScore, 0.60);
        }
        return DocumentQualityAssessment.builder()
                .profile(profile)
                .level(DocumentQualityLevel.fromScore(safeScore))
                .score(round(safeScore))
                .charCount(stats.charCount)
                .chineseCharCount(stats.chineseCharCount)
                .englishWordCount(stats.englishWordCount)
                .paragraphCount(stats.paragraphCount)
                .sentenceCount(stats.sentenceCount)
                .noiseCharCount(stats.noiseCharCount)
                .noiseRatio(round(stats.noiseRatio))
                .dimensionScores(roundDimensions(dimensions))
                .keywords(distinctLimit(keywords, 12))
                .issues(distinctLimit(issues, 20))
                .suggestions(distinctLimit(suggestions, 20))
                .build();
    }

    private List<String> commonIssues(TextStats stats) {
        List<String> issues = new ArrayList<>();
        if (stats.charCount < 20) {
            issues.add("TOO_SHORT");
        }
        if (stats.noiseRatio > 0.15) {
            issues.add("HIGH_NOISE_RATIO");
        }
        if (stats.duplicateLineRatio > 0.35) {
            issues.add("DUPLICATE_CONTENT");
        }
        if (stats.sentenceCount <= 1 && stats.charCount > 80) {
            issues.add("LOW_SENTENCE_STRUCTURE");
        }
        if (stats.structuredLineCount == 0 && stats.charCount > 180) {
            issues.add("LOW_DOCUMENT_STRUCTURE");
        }
        return issues;
    }

    private List<String> commonSuggestions(Collection<String> issues) {
        List<String> suggestions = new ArrayList<>();
        if (issues.contains("TOO_SHORT")) {
            suggestions.add("补充足够的正文内容，避免过短片段直接进入知识库");
        }
        if (issues.contains("HIGH_NOISE_RATIO")) {
            suggestions.add("清理乱码、控制字符、无意义符号和格式噪声");
        }
        if (issues.contains("DUPLICATE_CONTENT")) {
            suggestions.add("删除重复段落，保留最完整的一份说明");
        }
        if (issues.contains("LOW_DOCUMENT_STRUCTURE")) {
            suggestions.add("增加标题、编号、列表或问答结构，提升阅读和检索效果");
        }
        if (issues.contains("LOW_SENTENCE_STRUCTURE")) {
            suggestions.add("拆分过长句子，让规则和证据更容易被检索命中");
        }
        return suggestions;
    }

    private double lengthScore(int length, int minUseful, int idealMin, int idealMax, int maxUseful) {
        if (length <= 0 || length < minUseful) {
            return 0.0;
        }
        if (length < idealMin) {
            return 0.45 + 0.55 * (length - minUseful) / Math.max(1.0, idealMin - minUseful);
        }
        if (length <= idealMax) {
            return 1.0;
        }
        if (length <= maxUseful) {
            return 1.0 - 0.35 * (length - idealMax) / Math.max(1.0, maxUseful - idealMax);
        }
        return 0.55;
    }

    private double keywordDiversityScore(TextStats stats) {
        if (stats.tokens.isEmpty()) {
            return 0.0;
        }
        int usefulTokens = (int) stats.tokens.stream()
                .filter(token -> token.length() > 1)
                .filter(token -> !STOP_WORDS.contains(token))
                .count();
        int uniqueUseful = (int) stats.uniqueTokens.stream()
                .filter(token -> token.length() > 1)
                .filter(token -> !STOP_WORDS.contains(token))
                .count();
        double volume = Math.min(1.0, usefulTokens / 30.0);
        double diversity = usefulTokens == 0 ? 0.0 : Math.min(1.0, (double) uniqueUseful / usefulTokens * 1.8);
        return clamp(volume * 0.55 + diversity * 0.45);
    }

    private double structureScore(TextStats stats) {
        if (stats.blank) {
            return 0.0;
        }
        double paragraph = stats.paragraphCount >= 2 ? 0.25 : 0.10;
        double sentence = stats.sentenceCount >= 3 ? 0.25 : stats.sentenceCount >= 1 ? 0.12 : 0.0;
        double structured = Math.min(0.35, stats.structuredLineCount * 0.12);
        double averageSentenceLength = stats.sentenceCount == 0 ? stats.charCount : (double) stats.charCount / stats.sentenceCount;
        double sentenceLength = averageSentenceLength <= 80 ? 0.15 : averageSentenceLength <= 140 ? 0.08 : 0.0;
        return clamp(paragraph + sentence + structured + sentenceLength);
    }

    private double readabilityScore(TextStats stats) {
        if (stats.blank) {
            return 0.0;
        }
        double noisePenalty = Math.min(0.55, stats.noiseRatio * 2.4);
        double duplicatePenalty = Math.min(0.25, stats.duplicateLineRatio * 0.7);
        double longSentencePenalty = 0.0;
        if (stats.sentenceCount > 0) {
            double averageSentenceLength = (double) stats.charCount / stats.sentenceCount;
            if (averageSentenceLength > 120) {
                longSentencePenalty = 0.15;
            } else if (averageSentenceLength > 80) {
                longSentencePenalty = 0.08;
            }
        }
        return clamp(1.0 - noisePenalty - duplicatePenalty - longSentencePenalty);
    }

    private double coverageScore(String content, List<String> terms, int idealMatches) {
        return coverageScore(content, terms, idealMatches, false);
    }

    private double coverageScore(String content, List<String> terms, int idealMatches, boolean ignoreNegated) {
        int matches = 0;
        for (String term : terms) {
            if (termCovered(content, term, ignoreNegated)) {
                matches++;
            }
        }
        return clamp((double) matches / Math.max(1, idealMatches));
    }

    private double retrievabilityScore(TextStats stats, List<String> primaryTerms, List<String> secondaryTerms) {
        double keyword = coverageScore(stats.content, primaryTerms, Math.min(4, Math.max(1, primaryTerms.size())));
        double supporting = coverageScore(stats.content, secondaryTerms, Math.min(4, Math.max(1, secondaryTerms.size())));
        double density = keywordDiversityScore(stats);
        double structure = structureScore(stats);
        return clamp(keyword * 0.35 + supporting * 0.25 + density * 0.20 + structure * 0.20);
    }

    private double reviewEvidenceScore(TextStats stats) {
        boolean hasConcreteDetail = stats.content.matches(".*(点了|买了|排队|等了|分钟|小时|人均|元|套餐|菜|店员|上菜|停车|位置).*");
        boolean hasScenario = stats.content.matches(".*(聚餐|约会|带娃|朋友|同事|周末|午餐|晚餐|外卖).*");
        double detail = hasConcreteDetail ? 0.35 : 0.0;
        double scenario = hasScenario ? 0.20 : 0.0;
        double aspect = coverageScore(stats.content, REVIEW_ASPECTS, 3) * 0.30;
        double length = lengthScore(stats.charCount, 20, 60, 500, 1200) * 0.15;
        return clamp(detail + scenario + aspect + length);
    }

    private double sentimentSignalScore(String content) {
        List<String> positive = Arrays.asList("好", "不错", "满意", "推荐", "喜欢", "实惠", "干净", "热情", "惊喜");
        List<String> negative = Arrays.asList("差", "失望", "踩雷", "太慢", "难吃", "贵", "吵", "脏", "冷淡", "排队久");
        boolean hasPositive = positive.stream().anyMatch(term -> containsIgnoreCase(content, term));
        boolean hasNegative = negative.stream().anyMatch(term -> containsIgnoreCase(content, term));
        if (hasPositive && hasNegative) {
            return 1.0;
        }
        if (hasPositive || hasNegative) {
            return 0.75;
        }
        return 0.25;
    }

    private double spamSafetyScore(TextStats stats) {
        double score = 1.0;
        for (String term : REVIEW_NOISE_TERMS) {
            if (containsIgnoreCase(stats.content, term)) {
                score -= 0.25;
            }
        }
        if (stats.duplicateLineRatio > 0.25) {
            score -= 0.20;
        }
        if (stats.noiseRatio > 0.15) {
            score -= 0.20;
        }
        return clamp(score);
    }

    private List<String> mergeKeywords(TextStats stats, List<String> primaryTerms, List<String> secondaryTerms) {
        List<String> keywords = new ArrayList<>();
        primaryTerms.stream().filter(term -> containsIgnoreCase(stats.content, term)).forEach(keywords::add);
        secondaryTerms.stream().filter(term -> containsIgnoreCase(stats.content, term)).forEach(keywords::add);
        if (stats.content.matches(".*(人均|\\d+\\s*元|价格|价位|性价比).*")) {
            keywords.add("价格");
        }
        keywords.addAll(extractKeywords(stats, 8));
        return distinctLimit(keywords, 12);
    }

    private List<String> extractKeywords(TextStats stats, int limit) {
        Map<String, Long> counts = stats.tokens.stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .filter(token -> token.length() > 1)
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.groupingBy(token -> token, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<String> tokenize(String content) {
        List<String> tokens = new ArrayList<>();
        String[] roughTokens = TOKEN_SPLITTER.split(content);
        for (String roughToken : roughTokens) {
            String token = roughToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (containsChinese(token)) {
                tokens.addAll(extractChineseTerms(token));
            } else {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private List<String> extractChineseTerms(String text) {
        List<String> terms = new ArrayList<>();
        for (String term : POLICY_TOPICS) {
            if (text.contains(term)) {
                terms.add(term);
            }
        }
        for (String term : POLICY_COMPLETENESS_TERMS) {
            if (text.contains(term)) {
                terms.add(term);
            }
        }
        for (String term : REVIEW_ASPECTS) {
            if (text.contains(term)) {
                terms.add(term);
            }
        }
        if (terms.isEmpty() && text.length() >= 2 && text.length() <= 8) {
            terms.add(text);
        }
        return terms;
    }

    private int countNoiseCharacters(String content) {
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (Character.isLetterOrDigit(ch) || Character.isWhitespace(ch) || isChinese(ch) || isCommonPunctuation(ch)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private boolean isCommonPunctuation(char ch) {
        return "，。！？；：、“”‘’（）【】《》,.!?;:()[]#-*_/".indexOf(ch) >= 0;
    }

    private int countSentences(String content) {
        if (blank(content)) {
            return 0;
        }
        int count = 0;
        for (String sentence : SENTENCE_SPLITTER.split(content)) {
            if (!sentence.trim().isEmpty()) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private int countStructuredLines(String content) {
        int count = 0;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("^(#{1,6}\\s+.+|[-*+]\\s+.+|\\d+[.、].+|[一二三四五六七八九十]+[、.].+|Q[:：].+|A[:：].+)$")) {
                count++;
            }
        }
        return count;
    }

    private double duplicateLineRatio(String content) {
        List<String> lines = content.lines()
                .map(String::trim)
                .filter(line -> line.length() >= 8)
                .collect(Collectors.toList());
        if (lines.size() <= 1) {
            return 0.0;
        }
        Set<String> unique = new LinkedHashSet<>(lines);
        return (double) (lines.size() - unique.size()) / lines.size();
    }

    private int countMatches(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private Map<String, Double> dimensions(Object... values) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], (Double) values[i + 1]);
        }
        return result;
    }

    private double weighted(Map<String, Double> dimensions, Object... values) {
        double total = 0.0;
        for (int i = 0; i < values.length; i += 2) {
            String key = (String) values[i];
            double weight = (Double) values[i + 1];
            total += dimensions.getOrDefault(key, 0.0) * weight;
        }
        return clamp(total);
    }

    private Map<String, Double> roundDimensions(Map<String, Double> dimensions) {
        Map<String, Double> rounded = new LinkedHashMap<>();
        dimensions.forEach((key, value) -> rounded.put(key, round(value)));
        return rounded;
    }

    private List<String> distinctLimit(Collection<String> values, int limit) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<String> list(String value) {
        List<String> result = new ArrayList<>();
        result.add(value);
        return result;
    }

    private boolean containsIgnoreCase(String content, String term) {
        return content != null && term != null
                && content.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private boolean termCovered(String content, String term, boolean ignoreNegated) {
        if (content == null || term == null) {
            return false;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        String normalizedTerm = term.toLowerCase(Locale.ROOT);
        int index = normalizedContent.indexOf(normalizedTerm);
        while (index >= 0) {
            if (!ignoreNegated || !negatedAt(content, index)) {
                return true;
            }
            index = normalizedContent.indexOf(normalizedTerm, index + normalizedTerm.length());
        }
        return false;
    }

    private boolean negatedAt(String content, int index) {
        int start = Math.max(0, index - 8);
        String prefix = content.substring(start, index);
        if (prefix.matches(".*(没有|没有任何|未包含|缺少|不含|不支持|不适合|无).*")) {
            return true;
        }
        int sentenceStart = Math.max(
                Math.max(content.lastIndexOf('。', index), content.lastIndexOf('！', index)),
                Math.max(content.lastIndexOf('？', index), content.lastIndexOf('\n', index))) + 1;
        String sentencePrefix = content.substring(sentenceStart, index);
        return sentencePrefix.matches(".*(没有|没有任何|未包含|缺少|不含|不支持|不适合|无).*");
    }

    private boolean containsChinese(String text) {
        return CHINESE_CHAR.matcher(text).find();
    }

    private boolean isChinese(char ch) {
        return ch >= '\u4E00' && ch <= '\u9FFF';
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private double clamp(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static class TextStats {
        private String content;
        private boolean blank;
        private int charCount;
        private int chineseCharCount;
        private int englishWordCount;
        private int paragraphCount;
        private int sentenceCount;
        private int noiseCharCount;
        private double noiseRatio;
        private List<String> tokens = new ArrayList<>();
        private Set<String> uniqueTokens = new LinkedHashSet<>();
        private int structuredLineCount;
        private double duplicateLineRatio;
    }

    private static class ProfileScore {
        private final double totalScore;
        private final Map<String, Double> dimensionScores;
        private final List<String> keywords;
        private final List<String> issues;
        private final List<String> suggestions;

        private ProfileScore(double totalScore,
                             Map<String, Double> dimensionScores,
                             List<String> keywords,
                             List<String> issues,
                             List<String> suggestions) {
            this.totalScore = totalScore;
            this.dimensionScores = dimensionScores;
            this.keywords = keywords;
            this.issues = issues;
            this.suggestions = suggestions;
        }
    }
}
