package com.hmdp.ai.domain.workflow;

import java.util.Optional;

public interface WorkflowRepository {
    Optional<WorkflowDefinition> findVersion(String tenantId, String workspaceId, String workflowVersionId);

    Optional<WorkflowCatalogEntry> findWorkflow(String tenantId, String workspaceId, String workflowId);

    WorkflowCatalogEntry createWorkflow(WorkflowCatalogEntry workflow, String actorId);

    int lockAndNextVersion(String tenantId, String workspaceId, String workflowId);

    WorkflowDefinition createVersion(WorkflowDefinition workflow, String actorId);

    Optional<WorkflowDefinition> findVersionNumber(String tenantId, String workspaceId,
                                                   String workflowId, int version);

    WorkflowDefinition publish(String tenantId, String workspaceId, String workflowId, int version,
                               String actorId);
}
