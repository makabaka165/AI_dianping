package com.hmdp.ai.api.observability;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.dto.observability.RunInspectionItemResponse;
import com.hmdp.ai.application.dto.observability.RunUsageResponse;
import com.hmdp.ai.application.observability.RunInspectionApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/runs")
@RequireAiPermission(AiPermission.RUN_INSPECT)
public class RunInspectionController {
    private final RunInspectionApplicationService service;

    public RunInspectionController(RunInspectionApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{id}/nodes")
    public List<RunInspectionItemResponse> nodes(@PathVariable String id) { return service.nodes(id); }

    @GetMapping("/{id}/model-calls")
    public List<RunInspectionItemResponse> models(@PathVariable String id) { return service.models(id); }

    @GetMapping("/{id}/tool-calls")
    public List<RunInspectionItemResponse> tools(@PathVariable String id) { return service.tools(id); }

    @GetMapping("/{id}/retrievals")
    public List<RunInspectionItemResponse> retrievals(@PathVariable String id) {
        return service.retrievals(id);
    }

    @GetMapping("/{id}/artifacts")
    public List<RunInspectionItemResponse> artifacts(@PathVariable String id) {
        return service.artifacts(id);
    }

    @GetMapping("/{id}/usage")
    public RunUsageResponse usage(@PathVariable String id) { return service.usage(id); }
}
