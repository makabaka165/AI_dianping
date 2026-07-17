package com.hmdp.ai.domain.memory;

public final class MemoryPolicy {
    private final MemoryType memoryType;
    private final long ttlSeconds;
    private final boolean persistToMysql;

    public MemoryPolicy(MemoryType memoryType, long ttlSeconds, boolean persistToMysql) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("ttlSeconds must be positive");
        this.memoryType = memoryType;
        this.ttlSeconds = ttlSeconds;
        this.persistToMysql = persistToMysql;
    }

    public MemoryType getMemoryType() { return memoryType; }
    public long getTtlSeconds() { return ttlSeconds; }
    public boolean isPersistToMysql() { return persistToMysql; }
}
