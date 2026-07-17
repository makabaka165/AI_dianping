package com.hmdp.ai.domain.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class AuthorizationContext {
    private final Set<AiPermission> permissions;
    public AuthorizationContext(Set<AiPermission> permissions) {
        this.permissions = permissions == null || permissions.isEmpty()
                ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }
    public Set<AiPermission> getPermissions() { return permissions; }
    public boolean has(AiPermission permission) { return permissions.contains(AiPermission.ADMIN) || permissions.contains(permission); }
}
