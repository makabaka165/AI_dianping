package com.hmdp.config;

import lombok.Builder;
import lombok.Data;

/**
 * Carries request-scoped AI metadata into LangChain4j tools.
 */
public final class AiRequestContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private AiRequestContext() {
    }

    public static void set(Context context) {
        HOLDER.set(context);
    }

    public static Context get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static String currentUserId() {
        Context context = get();
        return context == null ? null : context.getUserId();
    }

    public static String currentSessionId() {
        Context context = get();
        return context == null ? null : context.getSessionId();
    }

    public static String currentMemoryId() {
        Context context = get();
        return context == null ? null : context.getMemoryId();
    }

    @Data
    @Builder
    public static class Context {
        private String userId;
        private String sessionId;
        private String memoryId;
        private String traceId;
        private String sourceEndpoint;
    }
}
