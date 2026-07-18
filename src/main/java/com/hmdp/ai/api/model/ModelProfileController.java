package com.hmdp.ai.api.model;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.model.CreateModelProfileRequest;
import com.hmdp.ai.application.dto.model.ModelHealthResponse;
import com.hmdp.ai.application.dto.model.ModelProfileResponse;
import com.hmdp.ai.application.dto.model.UpdateModelProfileRequest;
import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.model.ModelProfileApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/model-profiles")
@RequireAiPermission(AiPermission.MODEL_MANAGE)
@Validated
public class ModelProfileController {
    private final ModelProfileApplicationService applicationService;

    public ModelProfileController(ModelProfileApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ModelProfileResponse create(@Valid @RequestBody CreateModelProfileRequest request) {
        return applicationService.create(request);
    }

    @GetMapping
    public PageResponse<ModelProfileResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.list(page, size);
    }

    @PutMapping("/{id}")
    public ModelProfileResponse update(@PathVariable String id,
                                       @Valid @RequestBody UpdateModelProfileRequest request) {
        return applicationService.update(id, request);
    }

    @PostMapping("/{id}/health-check")
    public ModelHealthResponse healthCheck(@PathVariable String id) {
        return applicationService.healthCheck(id);
    }
}
