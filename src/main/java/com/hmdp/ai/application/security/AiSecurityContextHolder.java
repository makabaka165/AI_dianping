package com.hmdp.ai.application.security;

import com.hmdp.ai.domain.security.AiSecurityContext;

public final class AiSecurityContextHolder {
    private static final ThreadLocal<AiSecurityContext> HOLDER = new ThreadLocal<>();
    private AiSecurityContextHolder() { }
    public static void set(AiSecurityContext context) { HOLDER.set(context); }
    public static AiSecurityContext require() {
        AiSecurityContext context = HOLDER.get();
        if (context == null) throw new IllegalStateException("AI security context is not available");
        return context;
    }
    public static void clear() { HOLDER.remove(); }
}
