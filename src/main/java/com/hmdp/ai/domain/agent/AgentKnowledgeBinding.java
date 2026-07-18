package com.hmdp.ai.domain.agent;

public final class AgentKnowledgeBinding {
    private final String knowledgeBaseId;
    private final int knowledgeBaseVersion;
    private final String knowledgeBaseVersionId;
    private final String indexVersion;
    private final String indexStatus;

    public AgentKnowledgeBinding(String knowledgeBaseId, int knowledgeBaseVersion,
                                 String knowledgeBaseVersionId, String indexVersion, String indexStatus) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.knowledgeBaseVersion = knowledgeBaseVersion;
        this.knowledgeBaseVersionId = knowledgeBaseVersionId;
        this.indexVersion = indexVersion;
        this.indexStatus = indexStatus;
    }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public int getKnowledgeBaseVersion() { return knowledgeBaseVersion; }
    public String getKnowledgeBaseVersionId() { return knowledgeBaseVersionId; }
    public String getIndexVersion() { return indexVersion; }
    public String getIndexStatus() { return indexStatus; }
}
