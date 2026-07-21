package com.hmdp.ai.runtime.memory;

import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.memory.MessageRecord;
import com.hmdp.ai.domain.run.ExecutionContext;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConversationMemoryRetriever {
  private static final int MAX_CHARACTERS = 12_000;
  private final MemoryRepository repository;

  public ConversationMemoryRetriever(MemoryRepository repository) {
    this.repository = repository;
  }

  public String retrieve(ExecutionContext context) {
    if (context.getConversationId() == null) return "";
    List<MessageRecord> messages =
        repository.findMessages(
            context.getTenantId(), context.getWorkspaceId(), context.getConversationId(), 0, 30);
    StringBuilder summary = new StringBuilder();
    for (MessageRecord message : messages) {
      String content = message.getContent();
      if (content == null || content.trim().isEmpty()) continue;
      String line = message.getRole().name() + ": " + content.trim() + '\n';
      if (summary.length() + line.length() > MAX_CHARACTERS) break;
      summary.append(line);
    }
    return summary.toString();
  }
}
