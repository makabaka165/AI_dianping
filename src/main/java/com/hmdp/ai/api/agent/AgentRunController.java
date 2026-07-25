package com.hmdp.ai.api.agent;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.application.dto.agent.AgentRunDetailResponse;
import com.hmdp.ai.application.dto.agent.AgentRunRequest;
import com.hmdp.ai.application.dto.agent.AgentRunSummaryResponse;
import com.hmdp.ai.application.dto.agent.ResumeAgentRunRequest;
import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.agent.AgentRunApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/agent-runs")
@RequireAiPermission(AiPermission.AGENT_RUN)
@Validated
public class AgentRunController {
    private final AgentRunApplicationService applicationService;

    public AgentRunController(AgentRunApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public AgentRunCreatedResponse create(@Valid @RequestBody AgentRunRequest request) {
        return applicationService.create(request);
    }

    @GetMapping
    public PageResponse<AgentRunSummaryResponse> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo) {
        return applicationService.list(page, size, agentId, status, createdFrom, createdTo);
    }

    @GetMapping("/{runId}")
    public AgentRunDetailResponse get(@PathVariable String runId) {
        return applicationService.get(runId);
    }

    @PostMapping("/{runId}/cancel")
    public AgentRunCreatedResponse cancel(@PathVariable String runId) {
        return applicationService.cancel(runId);
    }

    @PostMapping("/{runId}/retry")
    public AgentRunCreatedResponse retry(@PathVariable String runId) {
        return applicationService.retry(runId);
    }

    @PostMapping("/{runId}/resume")
    public AgentRunCreatedResponse resume(@PathVariable String runId,
                                          @Valid @RequestBody ResumeAgentRunRequest request) {
        return applicationService.resume(runId, request);
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long afterSequence) {
        return applicationService.openEvents(runId, Math.max(0, afterSequence));
    }
}
