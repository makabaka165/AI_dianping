package com.hmdp.ai.api.prompt;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.prompt.CreatePromptRequest;
import com.hmdp.ai.application.dto.prompt.CreatePromptVersionRequest;
import com.hmdp.ai.application.dto.prompt.PromptResponse;
import com.hmdp.ai.application.dto.prompt.PromptRollbackRequest;
import com.hmdp.ai.application.dto.prompt.PromptVersionResponse;
import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.prompt.PromptApplicationService;
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
@RequestMapping("/api/v1/prompts")
@RequireAiPermission(AiPermission.PROMPT_MANAGE)
@Validated
public class PromptController {
    private final PromptApplicationService applicationService;

    public PromptController(PromptApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public PromptResponse create(@Valid @RequestBody CreatePromptRequest request) {
        return applicationService.create(request);
    }

    @GetMapping
    public PageResponse<PromptResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.list(page, size);
    }

    @PostMapping("/{id}/versions")
    public PromptVersionResponse createVersion(@PathVariable String id,
                                               @Valid @RequestBody CreatePromptVersionRequest request) {
        return applicationService.createVersion(id, request);
    }

    @GetMapping("/{id}/versions")
    public List<PromptVersionResponse> versions(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.versions(id, page, size);
    }

    @PostMapping("/{id}/versions/{version}/publish")
    public PromptVersionResponse publish(@PathVariable String id, @PathVariable @Min(1) int version) {
        return applicationService.publish(id, version);
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public PromptVersionResponse rollback(@PathVariable String id, @PathVariable @Min(1) int version,
                                          @Valid @RequestBody PromptRollbackRequest request) {
        return applicationService.rollback(id, version, request);
    }

    @GetMapping("/{id}/versions/{left}/diff/{right}")
    public VersionDiffResponse diff(@PathVariable String id, @PathVariable @Min(1) int left,
                                    @PathVariable @Min(1) int right) {
        return applicationService.diff(id, left, right);
    }
}
