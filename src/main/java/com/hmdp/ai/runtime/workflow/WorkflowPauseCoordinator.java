package com.hmdp.ai.runtime.workflow;

import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class WorkflowPauseCoordinator {
    private final WorkflowStateRepository states;
    private final RunRepository runs;

    public WorkflowPauseCoordinator(WorkflowStateRepository states, RunRepository runs) {
        this.states = states;
        this.runs = runs;
    }

    @Transactional
    public void pause(ExecutionContext context, WorkflowState state, WorkflowStateStatus stateStatus,
                      RunStatus runStatus, String nodeCode, String resumeTokenHash, Instant expiresAt) {
        states.saveWaiting(state, stateStatus, nodeCode, resumeTokenHash, expiresAt, context.getUserId());
        if (!runs.markWaiting(context.getTenantId(), context.getWorkspaceId(), context.getRunId(), runStatus,
                resumeTokenHash, expiresAt, context.getUserId())) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                    "run is no longer eligible to enter a waiting state");
        }
    }
}
