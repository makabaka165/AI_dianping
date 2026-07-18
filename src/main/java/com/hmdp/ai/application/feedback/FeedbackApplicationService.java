package com.hmdp.ai.application.feedback;
import com.hmdp.ai.application.dto.feedback.CreateFeedbackRequest;import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.feedback.*;import com.hmdp.ai.domain.memory.MemoryRepository;import com.hmdp.ai.domain.run.*;
import com.hmdp.ai.domain.security.*;import com.hmdp.ai.shared.exception.AiPlatformException;import com.hmdp.ai.shared.id.AiIdGenerator;import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;import java.time.Instant;
@Service public class FeedbackApplicationService {private final FeedbackRepository feedback;private final MemoryRepository memory;
    private final RunRepository runs;private final AiAccessGuard access;private final AiIdGenerator ids;
    public FeedbackApplicationService(FeedbackRepository feedback,MemoryRepository memory,RunRepository runs,AiAccessGuard access,AiIdGenerator ids){
        this.feedback=feedback;this.memory=memory;this.runs=runs;this.access=access;this.ids=ids;}
    public FeedbackRecord create(CreateFeedbackRequest request){AiSecurityContext c=access.require(AiPermission.FEEDBACK_SUBMIT);
        AgentRunRecord run=runs.find(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),request.getRunId())
                .orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"run not found"));
        if(!run.getUserId().equals(c.getUserId())&&!c.getAuthorization().has(AiPermission.ADMIN))throw new AiPlatformException(ErrorCode.FORBIDDEN,"run access denied");
        if(request.getMessageId()!=null&&!memory.messageBelongsToRun(run.getTenantId(),run.getWorkspaceId(),request.getMessageId(),run.getId()))
            throw new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"message not found for run");
        if(request.getNodeRunId()!=null&&!feedback.nodeBelongsToRun(run.getTenantId(),run.getWorkspaceId(),request.getNodeRunId(),run.getId()))
            throw new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"node run not found for run");
        if(request.getRating()==null&&request.getTags().isEmpty()&&blank(request.getComment())&&blank(request.getCorrectedAnswer()))
            throw new IllegalArgumentException("feedback content is required");
        return feedback.create(new FeedbackRecord(ids.nextId(),run.getTenantId(),run.getWorkspaceId(),run.getId(),
                request.getMessageId(),request.getNodeRunId(),request.getRating(),request.getTags(),request.getComment(),
                request.getCorrectedAnswer(),"PENDING","ACTIVE",c.getUserId(),Instant.now()));}
    private boolean blank(String v){return v==null||v.trim().isEmpty();}}
