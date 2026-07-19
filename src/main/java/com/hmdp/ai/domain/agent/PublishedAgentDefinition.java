package com.hmdp.ai.domain.agent;

import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.run.VersionSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PublishedAgentDefinition {
    private final AgentDefinition agent;
    private final AgentVersion version;
    private final ModelProfile modelProfile;
    private final ModelProfileVersion modelProfileVersion;
    private final PromptVersion promptVersion;
    private final String workflowId;
    private final int workflowVersion;
    private final String workflowStatus;
    private final List<AgentToolBinding> tools;
    private final List<AgentKnowledgeBinding> knowledgeBases;
    private final VersionSnapshot versionSnapshot;

    public PublishedAgentDefinition(AgentDefinition agent, AgentVersion version, ModelProfile modelProfile,
                                    PromptVersion promptVersion, String workflowId, int workflowVersion,
                                    String workflowStatus, List<AgentToolBinding> tools,
                                    List<AgentKnowledgeBinding> knowledgeBases, VersionSnapshot versionSnapshot) {
        this(agent, version, modelProfile, null, promptVersion, workflowId, workflowVersion, workflowStatus,
                tools, knowledgeBases, versionSnapshot);
    }

    public PublishedAgentDefinition(AgentDefinition agent, AgentVersion version, ModelProfile modelProfile,
                                    ModelProfileVersion modelProfileVersion, PromptVersion promptVersion,
                                    String workflowId, int workflowVersion, String workflowStatus,
                                    List<AgentToolBinding> tools, List<AgentKnowledgeBinding> knowledgeBases,
                                    VersionSnapshot versionSnapshot) {
        this.agent = agent;
        this.version = version;
        this.modelProfile = modelProfile;
        this.modelProfileVersion = modelProfileVersion;
        this.promptVersion = promptVersion;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.workflowStatus = workflowStatus;
        this.tools = immutable(tools);
        this.knowledgeBases = immutable(knowledgeBases);
        this.versionSnapshot = versionSnapshot;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
                ? Collections.emptyList() : values));
    }

    public AgentDefinition getAgent() { return agent; }
    public AgentVersion getVersion() { return version; }
    public ModelProfile getModelProfile() { return modelProfile; }
    public ModelProfileVersion getModelProfileVersion() { return modelProfileVersion; }
    public PromptVersion getPromptVersion() { return promptVersion; }
    public String getWorkflowId() { return workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public String getWorkflowStatus() { return workflowStatus; }
    public List<AgentToolBinding> getTools() { return tools; }
    public List<AgentKnowledgeBinding> getKnowledgeBases() { return knowledgeBases; }
    public VersionSnapshot getVersionSnapshot() { return versionSnapshot; }
}
