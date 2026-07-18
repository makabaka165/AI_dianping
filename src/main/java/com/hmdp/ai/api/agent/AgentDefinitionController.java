package com.hmdp.ai.api.agent;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.agent.AgentResponse;
import com.hmdp.ai.application.dto.agent.AgentRollbackRequest;
import com.hmdp.ai.application.dto.agent.AgentVersionResponse;
import com.hmdp.ai.application.dto.agent.CreateAgentRequest;
import com.hmdp.ai.application.dto.agent.CreateAgentVersionRequest;
import com.hmdp.ai.application.dto.agent.PublishValidationResponse;
import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.agent.AgentDefinitionApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
@RequireAiPermission(AiPermission.AGENT_MANAGE)
@Validated
public class AgentDefinitionController {
    private final AgentDefinitionApplicationService applicationService;

    public AgentDefinitionController(AgentDefinitionApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public AgentResponse create(@Valid @RequestBody CreateAgentRequest request) {
        return applicationService.create(request);
    }

    @GetMapping
    public PageResponse<AgentResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.list(page, size);
    }

    @GetMapping("/{id}")
    public AgentResponse get(@PathVariable String id) {
        return applicationService.get(id);
    }

    @PostMapping("/{id}/versions")
    public AgentVersionResponse createVersion(@PathVariable String id,
                                              @Valid @RequestBody CreateAgentVersionRequest request) {
        return applicationService.createVersion(id, request);
    }

    @GetMapping("/{id}/versions")
    public List<AgentVersionResponse> versions(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.versions(id, page, size);
    }

    @GetMapping("/{id}/versions/{version}")
    public AgentVersionResponse version(@PathVariable String id, @PathVariable @Min(1) int version) {
        return applicationService.version(id, version);
    }

    @PostMapping("/{id}/versions/{version}/validate")
    public PublishValidationResponse validate(@PathVariable String id, @PathVariable @Min(1) int version) {
        return applicationService.validate(id, version);
    }

    @PostMapping("/{id}/versions/{version}/publish")
    public AgentVersionResponse publish(@PathVariable String id, @PathVariable @Min(1) int version) {
        return applicationService.publish(id, version);
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public AgentVersionResponse rollback(@PathVariable String id, @PathVariable @Min(1) int version,
                                         @Valid @RequestBody AgentRollbackRequest request) {
        return applicationService.rollback(id, version, request);
    }

    @GetMapping("/{id}/versions/{left}/diff/{right}")
    public VersionDiffResponse diff(@PathVariable String id, @PathVariable @Min(1) int left,
                                    @PathVariable @Min(1) int right) {
        return applicationService.diff(id, left, right);
    }
}
