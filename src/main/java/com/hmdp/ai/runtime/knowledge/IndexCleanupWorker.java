package com.hmdp.ai.runtime.knowledge;

import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IndexCleanupWorker {
    private final KnowledgeRepository knowledge;
    private final KnowledgeIndexPort index;

    public IndexCleanupWorker(KnowledgeRepository knowledge, KnowledgeIndexPort index) {
        this.knowledge = knowledge;
        this.index = index;
    }

    @Scheduled(fixedDelayString = "${hmdp.ai.knowledge.index-cleanup-delay-ms:3600000}")
    public void cleanup() {
        for (String indexVersion : knowledge.findExpiredInactiveIndexes(20)) {
            index.drop(indexVersion);
            knowledge.markIndexDeleted(indexVersion, "index-cleaner");
        }
    }
}
