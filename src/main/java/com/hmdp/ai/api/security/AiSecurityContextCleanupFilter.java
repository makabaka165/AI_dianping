package com.hmdp.ai.api.security;

import com.hmdp.ai.application.security.AiSecurityContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiSecurityContextCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
            if (request.isAsyncStarted()) request.getAsyncContext().addListener(new CleanupListener());
        } finally {
            AiSecurityContextHolder.clear();
        }
    }

    private static final class CleanupListener implements AsyncListener {
        @Override public void onComplete(AsyncEvent event) { AiSecurityContextHolder.clear(); }
        @Override public void onTimeout(AsyncEvent event) { AiSecurityContextHolder.clear(); }
        @Override public void onError(AsyncEvent event) { AiSecurityContextHolder.clear(); }
        @Override public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
