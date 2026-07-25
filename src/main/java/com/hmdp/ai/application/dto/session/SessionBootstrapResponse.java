package com.hmdp.ai.application.dto.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionBootstrapResponse {
    private final SessionUserResponse user;
    private final List<SessionMembershipResponse> memberships;
    private final SessionScopeResponse defaultScope;

    public SessionBootstrapResponse(SessionUserResponse user, List<SessionMembershipResponse> memberships,
                                    SessionScopeResponse defaultScope) {
        this.user = user;
        this.memberships = Collections.unmodifiableList(new ArrayList<>(memberships));
        this.defaultScope = defaultScope;
    }

    public SessionUserResponse getUser() {
        return user;
    }

    public List<SessionMembershipResponse> getMemberships() {
        return memberships;
    }

    public SessionScopeResponse getDefaultScope() {
        return defaultScope;
    }
}
