package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.knowledge.OutboxEvent;
import com.hmdp.ai.domain.knowledge.OutboxRepository;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {
    private static final int MAX_ATTEMPTS = 10;
    private static final String CONSUMER = "redis-stream-publisher";

    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;

    public JdbcOutboxRepository(JdbcTemplate jdbc, AiIdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return jdbc.query("select id,tenant_id,workspace_id,aggregate_type,aggregate_id,event_type,"
                        + "payload_json,attempt from ai_outbox_event where ((status in ('PENDING','FAILED') "
                        + "and available_at<=?) or (status='PUBLISHING' and updated_at<?)) and attempt<? "
                        + "and deleted=0 order by available_at,id limit ?",
                (rs, row) -> new OutboxEvent(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("workspace_id"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"),
                        rs.getString("payload_json"), rs.getInt("attempt")),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now().minusSeconds(60)),
                MAX_ATTEMPTS, limit);
    }

    @Override
    public boolean claim(String id) {
        return jdbc.update("update ai_outbox_event set status='PUBLISHING',attempt=attempt+1,"
                        + "updated_by='outbox' where id=? and ((status in ('PENDING','FAILED') and available_at<=?) "
                        + "or (status='PUBLISHING' and updated_at<?)) and attempt<? and deleted=0",
                id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now().minusSeconds(60)),
                MAX_ATTEMPTS) == 1;
    }

    @Override
    public void published(String id) {
        jdbc.update("update ai_outbox_event set status='PUBLISHED',published_at=?,error_message=null,"
                        + "updated_by='outbox' where id=? and status='PUBLISHING'",
                Timestamp.from(Instant.now()), id);
    }

    @Override
    @Transactional
    public void failed(String id, String message) {
        List<OutboxEvent> events = jdbc.query("select id,tenant_id,workspace_id,aggregate_type,aggregate_id,"
                        + "event_type,payload_json,attempt from ai_outbox_event where id=? and status='PUBLISHING'",
                (rs, row) -> new OutboxEvent(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("workspace_id"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"),
                        rs.getString("payload_json"), rs.getInt("attempt")), id);
        if (events.isEmpty()) return;
        OutboxEvent event = events.get(0);
        String reason = limit(message, 1000);
        if (event.getAttempt() >= MAX_ATTEMPTS) {
            jdbc.update("update ai_outbox_event set status='DEAD',error_message=?,updated_by='outbox' "
                    + "where id=? and status='PUBLISHING'", reason, id);
            jdbc.update("insert into ai_outbox_dead_letter "
                            + "(id,outbox_event_id,consumer_name,payload_json,failure_reason,attempt,replay_status) "
                            + "values (?,?,?,?,?,?,'PENDING') on duplicate key update "
                            + "failure_reason=values(failure_reason),attempt=values(attempt)",
                    ids.nextId(), id, CONSUMER, event.getPayloadJson(), reason, event.getAttempt());
            return;
        }
        long backoff = Math.min(300, 1L << Math.min(event.getAttempt(), 20));
        jdbc.update("update ai_outbox_event set status='FAILED',available_at=?,error_message=?,"
                        + "updated_by='outbox' where id=? and status='PUBLISHING'",
                Timestamp.from(Instant.now().plusSeconds(backoff)), reason, id);
    }

    private String limit(String value, int max) {
        if (value == null) return "outbox publish failed";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
