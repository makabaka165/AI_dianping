package com.hmdp.ai.application.dto.memory;
import com.hmdp.ai.domain.memory.ConversationRecord;import com.hmdp.ai.domain.memory.MessageRecord;import java.util.List;
public final class ConversationResponse {private final ConversationRecord conversation;private final List<MessageRecord>messages;
    private final long total;public ConversationResponse(ConversationRecord conversation,List<MessageRecord>messages,long total){
        this.conversation=conversation;this.messages=messages;this.total=total;}
    public ConversationRecord getConversation(){return conversation;}public List<MessageRecord>getMessages(){return messages;}
    public long getTotal(){return total;}}
