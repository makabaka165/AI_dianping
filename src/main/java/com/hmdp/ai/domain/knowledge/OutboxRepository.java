package com.hmdp.ai.domain.knowledge;
import java.util.List;
public interface OutboxRepository {List<OutboxEvent> findPending(int limit);boolean claim(String eventId);void published(String eventId);void failed(String eventId,String message);}
