package com.hmdp.ai.runtime.workflow;

import com.hmdp.ai.domain.run.RunStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WorkflowPausedException extends RuntimeException {
    private final RunStatus runStatus;
    private final String nodeCode;
    private final String resumeToken;
    private final String resumeTokenHash;
    private final Instant expiresAt;
    private final List<String> questions;

    public WorkflowPausedException(RunStatus runStatus, String nodeCode, String resumeToken,
                                   String resumeTokenHash, Instant expiresAt, List<String> questions) {
        super("workflow paused at " + nodeCode);
        this.runStatus = runStatus;
        this.nodeCode = nodeCode;
        this.resumeToken = resumeToken;
        this.resumeTokenHash = resumeTokenHash;
        this.expiresAt = expiresAt;
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
    }

    public RunStatus getRunStatus() { return runStatus; }
    public String getNodeCode() { return nodeCode; }
    public String getResumeToken() { return resumeToken; }
    public String getResumeTokenHash() { return resumeTokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public List<String> getQuestions() { return questions; }
}
