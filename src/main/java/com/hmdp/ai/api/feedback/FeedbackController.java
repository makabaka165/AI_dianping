package com.hmdp.ai.api.feedback;
import com.hmdp.ai.api.security.RequireAiPermission;import com.hmdp.ai.application.dto.feedback.CreateFeedbackRequest;
import com.hmdp.ai.application.feedback.FeedbackApplicationService;import com.hmdp.ai.domain.feedback.FeedbackRecord;
import com.hmdp.ai.domain.security.AiPermission;import org.springframework.web.bind.annotation.*;import javax.validation.Valid;
@RestController @RequestMapping("/api/v1") public class FeedbackController {private final FeedbackApplicationService feedback;
    public FeedbackController(FeedbackApplicationService feedback){this.feedback=feedback;}
    @PostMapping("/feedback") @RequireAiPermission(AiPermission.FEEDBACK_SUBMIT) public FeedbackRecord create(@Valid @RequestBody CreateFeedbackRequest request){return feedback.create(request);}}
