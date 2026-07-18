package com.hmdp.ai.domain.memory;

import com.hmdp.ai.domain.run.AgentRunRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MemoryRepository {
    void recordCompletedRun(AgentRunRecord run, String userText, String outputJson, String answer,
                            String citationsJson, String usageJson, String explicitFact, Instant expiresAt);
    Optional<ConversationRecord> findConversation(String tenantId,String workspaceId,String userId,String id);
    List<MessageRecord> findMessages(String tenantId,String workspaceId,String conversationId,int offset,int limit);
    long countMessages(String tenantId,String workspaceId,String conversationId);
    boolean messageBelongsToRun(String tenantId,String workspaceId,String messageId,String runId);
    List<MemoryFact> findFacts(String tenantId,String workspaceId,String userId,int offset,int limit);
    long countFacts(String tenantId,String workspaceId,String userId);
    Optional<MemoryFact> confirmFact(String tenantId,String workspaceId,String userId,String factId,String actorId);
    Optional<MemoryFact> correctFact(String tenantId,String workspaceId,String userId,String factId,
                                    String value,String actorId);
    boolean deleteFact(String tenantId,String workspaceId,String userId,String factId,String actorId);
    int deleteAllFacts(String tenantId,String workspaceId,String userId,String actorId);
    boolean longTermMemoryEnabled(String tenantId,String workspaceId,String userId);
    void setLongTermMemoryEnabled(String tenantId,String workspaceId,String userId,boolean enabled,String actorId);
    void saveWorkingSnapshot(String tenantId,String workspaceId,String runId,String conversationId,
                             String snapshotJson,Instant expiresAt,String actorId);
}
