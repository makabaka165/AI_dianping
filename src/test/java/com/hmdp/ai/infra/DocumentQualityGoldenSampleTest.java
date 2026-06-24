package com.hmdp.ai.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQualityGoldenSampleTest {

    private static final String ROOT = "document-quality/";

    private final DocumentQualityAssessor assessor = new DocumentQualityAssessor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> goldenSamplesShouldMatchExpectedQuality() throws IOException {
        List<QualityCase> cases = loadCases();
        return cases.stream()
                .map(testCase -> DynamicTest.dynamicTest(testCase.name, () -> assertCase(testCase)));
    }

    private void assertCase(QualityCase testCase) throws IOException {
        String content = loadText(testCase.file);
        DocumentQualityAssessment assessment = assessor.assess(content, testCase.profile);
        String description = describe(testCase, assessment);

        assertThat(assessment.getProfile())
                .as(description)
                .isEqualTo(testCase.profile);
        if (testCase.minScore != null) {
            assertThat(assessment.getScore())
                    .as(description)
                    .isGreaterThanOrEqualTo(testCase.minScore);
        }
        if (testCase.maxScore != null) {
            assertThat(assessment.getScore())
                    .as(description)
                    .isLessThanOrEqualTo(testCase.maxScore);
        }
        if (testCase.expectedLevelAtLeast != null) {
            assertThat(levelRank(assessment.getLevel()))
                    .as(description)
                    .isGreaterThanOrEqualTo(levelRank(testCase.expectedLevelAtLeast));
        }
        if (testCase.expectedLevelAtMost != null) {
            assertThat(levelRank(assessment.getLevel()))
                    .as(description)
                    .isLessThanOrEqualTo(levelRank(testCase.expectedLevelAtMost));
        }
        assertThat(assessment.getDimensionScores().keySet())
                .as(description)
                .containsAll(safe(testCase.expectedDimensionsPresent));
        for (String expectedKeyword : safe(testCase.expectedKeywordsPresent)) {
            assertThat(assessment.getKeywords())
                    .as(description)
                    .anySatisfy(actualKeyword -> assertThat(actualKeyword).contains(expectedKeyword));
        }
        assertThat(assessment.getIssues())
                .as(description)
                .containsAll(safe(testCase.expectedIssuesPresent));
        List<String> absentIssues = safe(testCase.expectedIssuesAbsent);
        if (!absentIssues.isEmpty()) {
            assertThat(assessment.getIssues())
                    .as(description)
                    .doesNotContainAnyElementsOf(absentIssues);
        }
    }

    private List<QualityCase> loadCases() throws IOException {
        try (InputStream inputStream = new ClassPathResource(ROOT + "cases.json").getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<QualityCase>>() {
            });
        }
    }

    private String loadText(String file) throws IOException {
        ClassPathResource resource = new ClassPathResource(ROOT + file);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> safe(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private int levelRank(DocumentQualityLevel level) {
        if (level == DocumentQualityLevel.POOR) {
            return 0;
        }
        if (level == DocumentQualityLevel.FAIR) {
            return 1;
        }
        if (level == DocumentQualityLevel.GOOD) {
            return 2;
        }
        return 3;
    }

    private String describe(QualityCase testCase, DocumentQualityAssessment assessment) {
        return "case=" + testCase.name
                + ", score=" + assessment.getScore()
                + ", level=" + assessment.getLevel()
                + ", dimensions=" + assessment.getDimensionScores()
                + ", keywords=" + assessment.getKeywords()
                + ", issues=" + assessment.getIssues()
                + ", suggestions=" + assessment.getSuggestions();
    }

    public static class QualityCase {
        public String name;
        public String file;
        public DocumentQualityProfile profile;
        public Double minScore;
        public Double maxScore;
        public DocumentQualityLevel expectedLevelAtLeast;
        public DocumentQualityLevel expectedLevelAtMost;
        public List<String> expectedDimensionsPresent;
        public List<String> expectedKeywordsPresent;
        public List<String> expectedIssuesPresent;
        public List<String> expectedIssuesAbsent;
    }
}
