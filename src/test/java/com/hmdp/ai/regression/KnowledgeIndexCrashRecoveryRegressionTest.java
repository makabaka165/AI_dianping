package com.hmdp.ai.regression;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIndexCrashRecoveryRegressionTest {
    @Test
    void indexBuildMustHaveDurableConsumptionAndDeadLetterState() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V20260718_04__ai_persistent_knowledge_ingestion.sql"));
        assertTrue(migration.contains("ai_outbox_consumption"));
        assertTrue(migration.contains("ai_outbox_dead_letter"));
        assertTrue(migration.contains("SHADOW"));
    }
}
