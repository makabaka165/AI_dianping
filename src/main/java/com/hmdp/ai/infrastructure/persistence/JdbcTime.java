package com.hmdp.ai.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;

final class JdbcTime {
    private JdbcTime() { }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
