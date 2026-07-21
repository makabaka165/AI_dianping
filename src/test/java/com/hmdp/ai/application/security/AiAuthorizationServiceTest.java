package com.hmdp.ai.application.security;

import cn.dev33.satoken.stp.StpInterface;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAuthorizationServiceTest {
    @Test
    void resolvesPermissionsForBackgroundUserWithoutThreadLocalLogin() {
        JdbcTemplate jdbc = new MembershipJdbcTemplate();
        StpInterface permissions = new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return Arrays.asList("ai:tool_approve", "RUN_INSPECT");
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return Collections.emptyList();
            }
        };
        AiAuthorizationService service = new AiAuthorizationService(jdbc, permissions);

        AuthorizationContext context = service.authorize("user-7", "tenant-1", "workspace-1");

        assertTrue(context.has(AiPermission.TOOL_APPROVE));
        assertTrue(context.has(AiPermission.RUN_INSPECT));
    }

    private static final class MembershipJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (Integer.class.equals(requiredType)) return requiredType.cast(1);
            if (String.class.equals(requiredType)) return requiredType.cast("RUNNER");
            throw new AssertionError("unexpected query type: " + requiredType);
        }
    }
}
