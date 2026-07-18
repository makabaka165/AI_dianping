package com.hmdp.ai.domain.run;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.hmdp.ai.domain.artifact.ArtifactReference;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.artifact.ResponseBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AgentRunOutput {
    private final String answer;
    private final List<ResponseBlock> blocks;
    private final List<Citation> citations;
    private final List<ArtifactReference> artifacts;
    private final UsageSummary usage;
    private final List<String> warnings;
    private final RunStatus status;

    @JsonCreator
    public AgentRunOutput(@JsonProperty("answer") String answer,
                          @JsonProperty("blocks") List<ResponseBlock> blocks,
                          @JsonProperty("citations") List<Citation> citations,
                          @JsonProperty("artifacts") List<ArtifactReference> artifacts,
                          @JsonProperty("usage") UsageSummary usage,
                          @JsonProperty("warnings") List<String> warnings,
                          @JsonProperty("status") RunStatus status) {
        this.answer = answer;
        this.blocks = immutable(blocks);
        this.citations = immutable(citations);
        this.artifacts = immutable(artifacts);
        this.usage = usage;
        this.warnings = immutable(warnings);
        this.status = status;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
                ? Collections.emptyList() : values));
    }

    public String getAnswer() { return answer; }
    public List<ResponseBlock> getBlocks() { return blocks; }
    public List<Citation> getCitations() { return citations; }
    public List<ArtifactReference> getArtifacts() { return artifacts; }
    public UsageSummary getUsage() { return usage; }
    public List<String> getWarnings() { return warnings; }
    public RunStatus getStatus() { return status; }
}
