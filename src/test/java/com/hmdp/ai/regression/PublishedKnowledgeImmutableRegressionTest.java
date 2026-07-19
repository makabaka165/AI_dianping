package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PublishedKnowledgeImmutableRegressionTest {
    @Test
    void ingestionMustRejectPublishedVersionInsteadOfMutatingIt() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/hmdp/ai/application/knowledge/KnowledgeIngestionApplicationService.java"));
        assertFalse(source.contains("&& !\"PUBLISHED\".equals(target.getStatus())"),
                "PUBLISHED versions must never be accepted as ingestion targets");
    }
}
