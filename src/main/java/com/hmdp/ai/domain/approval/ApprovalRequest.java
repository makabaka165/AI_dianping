package com.hmdp.ai.domain.approval;

import java.time.Instant;

public final class ApprovalRequest {
    private final String id;
    private final String inputHash;
    private final String requestedBy;
    private final Instant expiresAt;

    public ApprovalRequest(String id, String inputHash, String requestedBy, Instant expiresAt) {
        this.id = id;
        this.inputHash = inputHash;
        this.requestedBy = requestedBy;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getInputHash() { return inputHash; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getExpiresAt() { return expiresAt; }
}
