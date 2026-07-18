package com.hmdp.ai.application.feedback;
import com.hmdp.ai.application.dto.feedback.CreateFeedbackRequest;import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.feedback.*;import com.hmdp.ai.domain.memory.MemoryRepository;import com.hmdp.ai.domain.run.*;import com.hmdp.ai.domain.security.*;
import com.hmdp.ai.shared.exception.AiPlatformException;import com.hmdp.ai.shared.id.AiIdGenerator;import org.junit.jupiter.api.Test;import java.time.Instant;import java.util.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;import static org.mockito.Mockito.*;
class FeedbackApplicationServiceTest {@Test void rejectsMessageFromAnotherRun(){FeedbackRepository feedback=mock(FeedbackRepository.class);MemoryRepository memory=mock(MemoryRepository.class);
    RunRepository runs=mock(RunRepository.class);AiAccessGuard access=mock(AiAccessGuard.class);AiSecurityContext context=new AiSecurityContext("user",new TenantContext("t"),new WorkspaceContext("w"),new AuthorizationContext(EnumSet.of(AiPermission.FEEDBACK_SUBMIT)),false);
    when(access.require(AiPermission.FEEDBACK_SUBMIT)).thenReturn(context);when(runs.find("t","w","run")).thenReturn(Optional.of(run()));when(memory.messageBelongsToRun("t","w","message","run")).thenReturn(false);
    CreateFeedbackRequest request=new CreateFeedbackRequest();request.setRunId("run");request.setMessageId("message");request.setRating(-1);
    FeedbackApplicationService service=new FeedbackApplicationService(feedback,memory,runs,access,new AiIdGenerator());
    assertThatThrownBy(()->service.create(request)).isInstanceOf(AiPlatformException.class).hasMessageContaining("message");verifyNoInteractions(feedback);}
    private AgentRunRecord run(){return new AgentRunRecord("run","t","w","user","s","c","a",1,RunStatus.COMPLETED,"BLOCKING","{}","{}","{}","{}","{}","{}","trace",null,1,null,null,Instant.now(),Instant.now(),Instant.now(),Instant.now(),Instant.now());}}
