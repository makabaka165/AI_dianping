package com.hmdp.ai.api.session;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.hmdp.ai.application.dto.session.SessionBootstrapResponse;
import com.hmdp.ai.application.session.SessionBootstrapApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bootstrap requires login but must not require tenant/workspace headers.
 * The AI permission interceptor is intentionally not applied to this path.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {
    private final SessionBootstrapApplicationService bootstrapService;

    public SessionController(SessionBootstrapApplicationService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @GetMapping("/bootstrap")
    @SaCheckLogin
    public SessionBootstrapResponse bootstrap() {
        return bootstrapService.bootstrap();
    }
}
