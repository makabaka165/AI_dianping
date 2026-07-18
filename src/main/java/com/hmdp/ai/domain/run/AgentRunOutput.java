package com.hmdp.ai.domain.run;

import com.hmdp.ai.domain.artifact.ArtifactReference;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.artifact.ResponseBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AgentRunOutput {
    private final String answer;
    private final List<ResponseBlock> blocks;
    private final List<Citation> citations;
    private final List<ArtifactReference> artifacts;
    private final UsageSummary usage;
    private final List<String> warnings;
    private final RunStatus status;

    public AgentRunOutput(String answer, List<ResponseBlock> blocks, List<Citation> citations,
                          List<ArtifactReference> artifacts, UsageSummary usage,
                          List<String> warnings, RunStatus status) {
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
