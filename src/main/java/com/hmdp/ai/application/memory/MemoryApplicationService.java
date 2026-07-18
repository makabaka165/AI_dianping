package com.hmdp.ai.application.memory;
import com.hmdp.ai.application.dto.PageResponse;import com.hmdp.ai.application.dto.memory.*;
import com.hmdp.ai.application.security.AiAccessGuard;import com.hmdp.ai.domain.memory.*;
import com.hmdp.ai.domain.security.*;import com.hmdp.ai.shared.exception.AiPlatformException;import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import java.util.stream.Collectors;
@Service public class MemoryApplicationService {private final MemoryRepository repository;private final AiAccessGuard access;
    public MemoryApplicationService(MemoryRepository repository,AiAccessGuard access){this.repository=repository;this.access=access;}
    public ConversationResponse conversation(String id,int page,int size){AiSecurityContext c=access.require(AiPermission.MEMORY_READ);
        ConversationRecord conversation=repository.findConversation(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),
                c.getUserId(),id).orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"conversation not found"));
        int offset=Math.multiplyExact(page-1,size);return new ConversationResponse(conversation,repository.findMessages(
                c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),id,offset,size),repository.countMessages(
                c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),id));}
    public PageResponse<MemoryFactResponse> facts(int page,int size){AiSecurityContext c=access.require(AiPermission.MEMORY_READ);
        int offset=Math.multiplyExact(page-1,size);return new PageResponse<>(repository.findFacts(c.getTenant().getTenantId(),
                c.getWorkspace().getWorkspaceId(),c.getUserId(),offset,size).stream().map(MemoryFactResponse::new)
                .collect(Collectors.toList()),repository.countFacts(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),
                c.getUserId()),page,size);}
    @Transactional public MemoryFactResponse confirm(String id){AiSecurityContext c=access.require(AiPermission.MEMORY_READ);
        return new MemoryFactResponse(repository.confirmFact(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),
                c.getUserId(),id,c.getUserId()).orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"memory not found")));}
    @Transactional public MemoryFactResponse correct(String id,UpdateMemoryFactRequest request){AiSecurityContext c=access.require(AiPermission.MEMORY_READ);
        return new MemoryFactResponse(repository.correctFact(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),
                c.getUserId(),id,request.getFactValue(),c.getUserId()).orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"memory not found")));}
    @Transactional public void delete(String id){AiSecurityContext c=access.require(AiPermission.MEMORY_DELETE);if(!repository.deleteFact(
            c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),c.getUserId(),id,c.getUserId()))throw new AiPlatformException(
            ErrorCode.AI_RESOURCE_NOT_FOUND,"memory not found");}
    @Transactional public int deleteAll(){AiSecurityContext c=access.require(AiPermission.MEMORY_DELETE);return repository.deleteAllFacts(
            c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),c.getUserId(),c.getUserId());}
    @Transactional public void setEnabled(boolean enabled){AiSecurityContext c=access.require(AiPermission.MEMORY_DELETE);
        repository.setLongTermMemoryEnabled(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),c.getUserId(),enabled,c.getUserId());}}
