package com.hmdp.ai.api.workflow;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.agent.PublishValidationResponse;
import com.hmdp.ai.application.dto.workflow.CreateWorkflowRequest;
import com.hmdp.ai.application.dto.workflow.CreateWorkflowVersionRequest;
import com.hmdp.ai.application.dto.workflow.WorkflowResponse;
import com.hmdp.ai.application.dto.workflow.WorkflowVersionResponse;
import com.hmdp.ai.application.workflow.WorkflowApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.Min;

@RestController @RequestMapping("/api/v1/workflows") @RequireAiPermission(AiPermission.WORKFLOW_MANAGE) @Validated
public class WorkflowController {
    private final WorkflowApplicationService workflows;
    public WorkflowController(WorkflowApplicationService workflows){this.workflows=workflows;}
    @PostMapping public WorkflowResponse create(@Valid @RequestBody CreateWorkflowRequest request){return workflows.create(request);}
    @PostMapping("/{id}/versions") public WorkflowVersionResponse createVersion(@PathVariable String id,@Valid @RequestBody CreateWorkflowVersionRequest request){return workflows.createVersion(id,request);}
    @GetMapping("/{id}/versions/{version}") public WorkflowVersionResponse version(@PathVariable String id,@PathVariable @Min(1) int version){return workflows.version(id,version);}
    @PostMapping("/{id}/versions/{version}/validate") public PublishValidationResponse validate(@PathVariable String id,@PathVariable @Min(1) int version){return workflows.validate(id,version);}
    @PostMapping("/{id}/versions/{version}/publish") public WorkflowVersionResponse publish(@PathVariable String id,@PathVariable @Min(1) int version){return workflows.publish(id,version);}
    @GetMapping("/{id}/versions/{left}/diff/{right}") public VersionDiffResponse diff(@PathVariable String id,@PathVariable @Min(1) int left,@PathVariable @Min(1) int right){return workflows.diff(id,left,right);}
}
