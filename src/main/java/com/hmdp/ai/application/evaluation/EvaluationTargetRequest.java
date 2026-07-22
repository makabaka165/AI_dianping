package com.hmdp.ai.application.evaluation;

import com.hmdp.ai.application.dto.evaluation.EvaluationExecutionOptions;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.security.AuthorizationContext;

import java.util.Objects;

public final class EvaluationTargetRequest {
    private final EvaluationCase evaluationCase;
    private final String targetType;
    private final String targetId;
    private final Integer targetVersion;
    private final EvaluationExecutionOptions options;
    private final String tenantId;
    private final String workspaceId;
    private final String actorId;
    private final AuthorizationContext authorization;

    public EvaluationTargetRequest(EvaluationCase evaluationCase, String targetType, String targetId,
                                   Integer targetVersion, EvaluationExecutionOptions options,
                                   String tenantId, String workspaceId, String actorId,
                                   AuthorizationContext authorization) {
        this.evaluationCase = Objects.requireNonNull(evaluationCase, "evaluationCase");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.targetVersion = targetVersion;
        this.options = options == null ? new EvaluationExecutionOptions() : options;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public EvaluationCase getEvaluationCase() { return evaluationCase; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public Integer getTargetVersion() { return targetVersion; }
    public int targetVersionOr(int fallback) { return targetVersion == null ? fallback : targetVersion; }
    public EvaluationExecutionOptions getOptions() { return options; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getActorId() { return actorId; }
    public AuthorizationContext getAuthorization() { return authorization; }
}
