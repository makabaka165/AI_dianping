package com.hmdp.ai.domain.run;

import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionContextTest {

    @Test
    void defensivelyCopiesCollectionsAndExposesImmutableState() {
        List<String> uris = new ArrayList<>(Collections.singletonList("kb:doc/1"));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("shopId", 1);
        ExecutionContext context = new ExecutionContext("tenant", "workspace", "user", "session",
                "conversation", "run", "agent", 1, "zh-CN", "Asia/Shanghai",
                Collections.emptyList(), uris, new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)),
                ExecutionBudget.defaults(), Instant.now().plusSeconds(30), variables, "trace");

        uris.add("kb:doc/2");
        variables.put("shopId", 2);

        assertEquals(1, context.getReferenceUris().size());
        assertEquals(1, context.getVariables().get("shopId"));
        assertThrows(UnsupportedOperationException.class,
                () -> context.getVariables().put("new", "value"));
    }
}
