package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.knowledge.IndexBuildRequestRepository;
import com.hmdp.ai.domain.knowledge.OutboxEvent;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcIndexBuildRequestRepository implements IndexBuildRequestRepository {
    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;

    public JdbcIndexBuildRequestRepository(JdbcTemplate jdbc, AiIdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public List<OutboxEvent> findAvailable(String consumerName, int limit) {
        return jdbc.query("select e.id,e.tenant_id,e.workspace_id,e.aggregate_type,e.aggregate_id,"
                        + "e.event_type,e.payload_json,coalesce(c.attempt,0) attempt from ai_outbox_event e "
                        + "left join ai_outbox_consumption c on c.outbox_event_id=e.id and c.consumer_name=? "
                        + "where e.event_type='INDEX_BUILD_REQUESTED' and e.deleted=0 and "
                        + "(c.id is null or (c.status in ('READY','FAILED') and c.available_at<=?) or "
                        + "(c.status='PROCESSING' and c.lease_until<?)) and coalesce(c.attempt,0)<10 "
                        + "order by e.created_at,e.id limit ?",
                (rs, row) -> new OutboxEvent(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("workspace_id"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"),
                        rs.getString("payload_json"), rs.getInt("attempt")),
                consumerName, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), limit);
    }

    @Override
    @Transactional
    public boolean claim(String eventId, String consumerName, String workerId, Duration lease) {
        jdbc.update("insert ignore into ai_outbox_consumption "
                        + "(id,outbox_event_id,consumer_name,status,attempt,available_at) "
                        + "values (?,?,?,'READY',0,?)",
                ids.nextId(), eventId, consumerName, Timestamp.from(Instant.now()));
        return jdbc.update("update ai_outbox_consumption set status='PROCESSING',attempt=attempt+1,"
                        + "failure_reason=null,claimed_at=?,claimed_by=?,lease_until=? where "
                        + "outbox_event_id=? and consumer_name=? and (status in ('READY','FAILED') "
                        + "or (status='PROCESSING' and lease_until<?)) and available_at<=?",
                Timestamp.from(Instant.now()), workerId, Timestamp.from(Instant.now().plus(lease)),
                eventId, consumerName, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())) == 1;
    }

    @Override
    public void complete(String eventId, String consumerName) {
        jdbc.update("update ai_outbox_consumption set status='CONSUMED',consumed_at=?,lease_until=null,"
                        + "updated_at=? where outbox_event_id=? and consumer_name=? and status='PROCESSING'",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), eventId, consumerName);
    }

    @Override
    @Transactional
    public boolean fail(String eventId, String consumerName, String payloadJson, String reason, int maxAttempts) {
        Integer attempt = jdbc.queryForObject("select attempt from ai_outbox_consumption "
                        + "where outbox_event_id=? and consumer_name=?", Integer.class, eventId, consumerName);
        int currentAttempt = attempt == null ? 1 : attempt;
        String safeReason = limit(reason, 1000);
        if (currentAttempt >= maxAttempts) {
            jdbc.update("update ai_outbox_consumption set status='DEAD',failure_reason=?,lease_until=null "
                            + "where outbox_event_id=? and consumer_name=?",
                    safeReason, eventId, consumerName);
            jdbc.update("insert into ai_outbox_dead_letter "
                            + "(id,outbox_event_id,consumer_name,payload_json,failure_reason,attempt,replay_status) "
                            + "values (?,?,?,?,?,?,'PENDING') on duplicate key update payload_json=values(payload_json),"
                            + "failure_reason=values(failure_reason),attempt=values(attempt),replay_status='PENDING'",
                    ids.nextId(), eventId, consumerName, payloadJson, safeReason, currentAttempt);
            return true;
        }
        long delaySeconds = Math.min(300, 1L << Math.min(20, currentAttempt));
        jdbc.update("update ai_outbox_consumption set status='FAILED',failure_reason=?,available_at=?,"
                        + "lease_until=null where outbox_event_id=? and consumer_name=?",
                safeReason, Timestamp.from(Instant.now().plusSeconds(delaySeconds)), eventId, consumerName);
        return false;
    }

    @Override
    @Transactional
    public boolean replay(String deadLetterId, String actorId) {
        List<java.util.Map<String, Object>> values = jdbc.queryForList(
                "select outbox_event_id,consumer_name from ai_outbox_dead_letter "
                        + "where id=? and replay_status='PENDING'", deadLetterId);
        if (values.isEmpty()) return false;
        String eventId = String.valueOf(values.get(0).get("outbox_event_id"));
        String consumer = String.valueOf(values.get(0).get("consumer_name"));
        jdbc.update("update ai_outbox_consumption set status='READY',attempt=0,failure_reason=null,"
                        + "available_at=?,claimed_at=null,claimed_by=null,lease_until=null "
                        + "where outbox_event_id=? and consumer_name=?",
                Timestamp.from(Instant.now()), eventId, consumer);
        return jdbc.update("update ai_outbox_dead_letter set replay_status='REPLAYED',replayed_at=?,"
                        + "updated_at=? where id=? and replay_status='PENDING'",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), deadLetterId) == 1;
    }

    private String limit(String value, int length) {
        if (value == null) return "unknown failure";
        return value.length() <= length ? value : value.substring(0, length);
    }
}
