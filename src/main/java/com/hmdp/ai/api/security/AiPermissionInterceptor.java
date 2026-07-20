package com.hmdp.ai.api.security;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.domain.security.*;
import com.hmdp.ai.application.security.AiSecurityContextHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AiPermissionInterceptor implements AsyncHandlerInterceptor {
    private final AiAuthorizationService authorizationService;
    public AiPermissionInterceptor(AiAuthorizationService authorizationService) { this.authorizationService = authorizationService; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod)) return true;
        if (!StpUtil.isLogin()) return reject(response, 401, "AI_UNAUTHENTICATED");
        HandlerMethod method = (HandlerMethod) handler;
        RequireAiPermission required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireAiPermission.class);
        if (required == null) required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireAiPermission.class);
        boolean v1 = request.getRequestURI().startsWith("/api/v1/");
        if (v1 && required == null) return reject(response, 403, "AI_PERMISSION_DECLARATION_REQUIRED");
        AiPermission permission = required == null ? defaultPermission(request) : required.value();
        String tenantId = header(request, "X-Tenant-Id", v1 ? null : "default");
        String workspaceId = header(request, "X-Workspace-Id", v1 ? null : "default");
        if (tenantId == null || workspaceId == null) return reject(response, 400, "AI_CONTEXT_REQUIRED");
        String userId = StpUtil.getLoginIdAsString();
        AuthorizationContext authorization = authorizationService.authorize(userId, tenantId, workspaceId);
        if (!authorizationService.hasPermission(authorization, permission)) return reject(response, 403, "AI_PERMISSION_DENIED");
        AiSecurityContextHolder.set(new AiSecurityContext(userId, new TenantContext(tenantId),
                new WorkspaceContext(workspaceId), authorization, false));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AiSecurityContextHolder.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        AiSecurityContextHolder.clear();
    }

    private AiPermission defaultPermission(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/document") || request.getRequestURI().contains("knowledge")) {
            return "GET".equals(request.getMethod()) ? AiPermission.KNOWLEDGE_READ : AiPermission.KNOWLEDGE_WRITE;
        }
        return AiPermission.AGENT_RUN;
    }

    private String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private boolean reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":\"" + code + "\"}");
        return false;
    }
}
