package com.hmdp.ai.application.workflow;

import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.agent.PublishValidationResponse;
import com.hmdp.ai.application.dto.workflow.CreateWorkflowRequest;
import com.hmdp.ai.application.dto.workflow.CreateWorkflowVersionRequest;
import com.hmdp.ai.application.dto.workflow.WorkflowEdgeRequest;
import com.hmdp.ai.application.dto.workflow.WorkflowNodeRequest;
import com.hmdp.ai.application.dto.workflow.WorkflowResponse;
import com.hmdp.ai.application.dto.workflow.WorkflowVersionResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.workflow.WorkflowCatalogEntry;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.json.VersionDiffService;
import com.hmdp.ai.shared.validation.ValidationResult;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkflowApplicationService {
    private final WorkflowRepository repository; private final WorkflowValidator validator;
    private final AiAccessGuard access; private final AiIdGenerator ids; private final ContentHashService hashes;
    private final VersionDiffService diffs;
    public WorkflowApplicationService(WorkflowRepository repository,WorkflowValidator validator,AiAccessGuard access,AiIdGenerator ids,ContentHashService hashes,VersionDiffService diffs){this.repository=repository;this.validator=validator;this.access=access;this.ids=ids;this.hashes=hashes;this.diffs=diffs;}

    @Transactional public WorkflowResponse create(CreateWorkflowRequest request){AiSecurityContext c=access.require(AiPermission.WORKFLOW_MANAGE);WorkflowCatalogEntry workflow=new WorkflowCatalogEntry(ids.nextId(),c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),request.getCode(),request.getName(),request.getDescription(),0,"ACTIVE");return new WorkflowResponse(repository.createWorkflow(workflow,c.getUserId()));}

    @Transactional public WorkflowVersionResponse createVersion(String workflowId,CreateWorkflowVersionRequest request){AiSecurityContext c=access.require(AiPermission.WORKFLOW_MANAGE);requireWorkflow(c,workflowId);int version=repository.lockAndNextVersion(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),workflowId);List<WorkflowNodeDefinition> nodes=request.getNodes().stream().map(n->node(n)).collect(Collectors.toList());List<WorkflowEdgeDefinition> edges=request.getEdges().stream().map(e->edge(e)).collect(Collectors.toList());Map<String,Object> content=content(request);WorkflowDefinition definition=new WorkflowDefinition(ids.nextId(),c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),workflowId,version,request.getInputSchema(),request.getOutputSchema(),request.getVariablesSchema(),request.getExecutionPolicyJson(),"DRAFT",nodes,edges,hashes.sha256(content),request.getChangeNote(),null,null);return new WorkflowVersionResponse(repository.createVersion(definition,c.getUserId()));}

    public WorkflowVersionResponse version(String workflowId,int version){AiSecurityContext c=access.require(AiPermission.WORKFLOW_MANAGE);return new WorkflowVersionResponse(requireVersion(c,workflowId,version));}
    public PublishValidationResponse validate(String workflowId,int version){AiSecurityContext c=access.require(AiPermission.WORKFLOW_MANAGE);return new PublishValidationResponse(validator.validate(requireVersion(c,workflowId,version)));}
    @Transactional public WorkflowVersionResponse publish(String workflowId,int version){AiSecurityContext c=access.require(AiPermission.WORKFLOW_MANAGE);WorkflowDefinition definition=requireVersion(c,workflowId,version);ValidationResult result=validator.validate(definition);if(!result.isValid())throw new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,"workflow version cannot be published",result.getIssues());return new WorkflowVersionResponse(repository.publish(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),workflowId,version,c.getUserId()));}
    public VersionDiffResponse diff(String workflowId,int left,int right){AiSecurityContext c=access.require(AiPermission.WORKFLOW_MANAGE);WorkflowDefinition a=requireVersion(c,workflowId,left),b=requireVersion(c,workflowId,right);return new VersionDiffResponse(left,right,diffs.diff(content(a),content(b)));}
    private WorkflowCatalogEntry requireWorkflow(AiSecurityContext c,String id){return repository.findWorkflow(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),id).orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"workflow not found"));}
    private WorkflowDefinition requireVersion(AiSecurityContext c,String id,int version){return repository.findVersionNumber(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),id,version).orElseThrow(()->new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,"workflow version not found"));}
    private WorkflowNodeDefinition node(WorkflowNodeRequest n){return new WorkflowNodeDefinition(ids.nextId(),n.getCode(),n.getType(),n.getName(),n.getConfigurationJson(),n.getInputMappingJson(),n.getOutputMappingJson(),n.getTimeoutMs(),n.getMaxAttempts());}
    private WorkflowEdgeDefinition edge(WorkflowEdgeRequest e){return new WorkflowEdgeDefinition(ids.nextId(),e.getSourceNodeCode(),e.getTargetNodeCode(),e.getConditionJson(),e.getPriority(),e.getLabel());}
    private Map<String,Object> content(CreateWorkflowVersionRequest r){Map<String,Object>m=new LinkedHashMap<>();m.put("inputSchema",r.getInputSchema());m.put("outputSchema",r.getOutputSchema());m.put("variablesSchema",r.getVariablesSchema());m.put("executionPolicyJson",r.getExecutionPolicyJson());m.put("nodes",r.getNodes());m.put("edges",r.getEdges());return m;}
    private Map<String,Object> content(WorkflowDefinition r){Map<String,Object>m=new LinkedHashMap<>();m.put("inputSchema",r.getInputSchema());m.put("outputSchema",r.getOutputSchema());m.put("variablesSchema",r.getVariablesSchema());m.put("executionPolicyJson",r.getExecutionPolicyJson());m.put("nodes",r.getNodes());m.put("edges",r.getEdges());return m;}
}
