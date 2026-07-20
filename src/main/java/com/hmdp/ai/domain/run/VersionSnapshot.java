package com.hmdp.ai.domain.run;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VersionSnapshot {
    private final String agentId;
    private final String agentCode;
    private final int agentVersion;
    private final String promptId;
    private final int promptVersion;
    private final String workflowId;
    private final int workflowVersion;
    private final String modelProfileId;
    private final String modelProfileVersionId;
    private final int modelProfileVersion;
    private final String modelProfileContentHash;
    private final int modelProfileRevision;
    private final Map<String, Integer> toolVersions;
    private final Map<String, Integer> knowledgeBaseVersions;
    private final Map<String, String> indexVersions;

    public VersionSnapshot(String agentId, int agentVersion, String promptId, int promptVersion,
                           String workflowId, int workflowVersion, String modelProfileId,
                           int modelProfileRevision, Map<String, Integer> toolVersions,
                           Map<String, Integer> knowledgeBaseVersions, Map<String, String> indexVersions) {
        this(agentId, agentId, agentVersion, promptId, promptVersion, workflowId, workflowVersion, modelProfileId,
                modelProfileId, modelProfileRevision, null, toolVersions, knowledgeBaseVersions, indexVersions);
    }

    public VersionSnapshot(String agentId, int agentVersion, String promptId, int promptVersion,
                           String workflowId, int workflowVersion, String modelProfileId,
                           String modelProfileVersionId, int modelProfileVersion, String modelProfileContentHash,
                           Map<String, Integer> toolVersions, Map<String, Integer> knowledgeBaseVersions,
                           Map<String, String> indexVersions) {
        this(agentId, agentId, agentVersion, promptId, promptVersion, workflowId, workflowVersion, modelProfileId,
                modelProfileVersionId, modelProfileVersion, modelProfileContentHash, toolVersions,
                knowledgeBaseVersions, indexVersions);
    }

    public VersionSnapshot(String agentId, String agentCode, int agentVersion, String promptId, int promptVersion,
                           String workflowId, int workflowVersion, String modelProfileId,
                           String modelProfileVersionId, int modelProfileVersion, String modelProfileContentHash,
                           Map<String, Integer> toolVersions, Map<String, Integer> knowledgeBaseVersions,
                           Map<String, String> indexVersions) {
        this.agentId = agentId;
        this.agentCode = agentCode;
        this.agentVersion = agentVersion;
        this.promptId = promptId;
        this.promptVersion = promptVersion;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.modelProfileId = modelProfileId;
        this.modelProfileVersionId = modelProfileVersionId;
        this.modelProfileVersion = modelProfileVersion;
        this.modelProfileContentHash = modelProfileContentHash;
        this.modelProfileRevision = modelProfileVersion;
        this.toolVersions = immutable(toolVersions);
        this.knowledgeBaseVersions = immutable(knowledgeBaseVersions);
        this.indexVersions = immutable(indexVersions);
    }

    private static <T> Map<String, T> immutable(Map<String, T> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value == null
                ? Collections.emptyMap() : value));
    }

    public String getAgentId() { return agentId; }
    public String getAgentCode() { return agentCode; }
    public int getAgentVersion() { return agentVersion; }
    public String getPromptId() { return promptId; }
    public int getPromptVersion() { return promptVersion; }
    public String getWorkflowId() { return workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public String getModelProfileId() { return modelProfileId; }
    public String getModelProfileVersionId() { return modelProfileVersionId; }
    public int getModelProfileVersion() { return modelProfileVersion; }
    public String getModelProfileContentHash() { return modelProfileContentHash; }
    public int getModelProfileRevision() { return modelProfileRevision; }
    public Map<String, Integer> getToolVersions() { return toolVersions; }
    public Map<String, Integer> getKnowledgeBaseVersions() { return knowledgeBaseVersions; }
    public Map<String, String> getIndexVersions() { return indexVersions; }
}
