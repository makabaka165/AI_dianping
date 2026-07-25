package com.hmdp.ai.api.agent;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.agent.AgentDefinitionApplicationService;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.agent.RunnableAgentResponse;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/runnable-agents")
@RequireAiPermission(AiPermission.AGENT_RUN)
@Validated
public class RunnableAgentController {
    private final AgentDefinitionApplicationService applicationService;

    public RunnableAgentController(AgentDefinitionApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public PageResponse<RunnableAgentResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.listRunnable(page, size);
    }
}
