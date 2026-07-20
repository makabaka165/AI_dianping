package com.hmdp.ai.api.approval;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.approval.ApprovalApplicationService;
import com.hmdp.ai.application.dto.approval.ApprovalDecisionRequest;
import com.hmdp.ai.application.dto.approval.ApprovalResponse;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/approvals")
@RequireAiPermission(AiPermission.TOOL_APPROVE)
public class ApprovalController {
    private final ApprovalApplicationService service;

    public ApprovalController(ApprovalApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApprovalResponse> list() { return service.list(); }

    @GetMapping("/{id}")
    public ApprovalResponse get(@PathVariable String id) { return service.get(id); }

    @PostMapping("/{id}/approve")
    public ApprovalResponse approve(@PathVariable String id,
                                    @Valid @RequestBody ApprovalDecisionRequest request) {
        return service.decide(id, "APPROVED", request.getReason());
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponse reject(@PathVariable String id,
                                   @Valid @RequestBody ApprovalDecisionRequest request) {
        return service.decide(id, "REJECTED", request.getReason());
    }
}
