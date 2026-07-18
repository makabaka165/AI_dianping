package com.hmdp.ai.shared.text;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class TextNormalizerTest {@Test void normalizesUnicodeWhitespaceAndBuildsChineseBigrams(){TextNormalizer normalizer=new TextNormalizer();assertEquals("ABC 测试文本",normalizer.normalize("ＡＢＣ\u00a0  测试文本"));String search=normalizer.searchText("服务态度 Excellent");assertTrue(search.contains("服务"));assertTrue(search.contains("务态"));assertTrue(search.contains("态度"));assertTrue(search.contains("excellent"));}}
