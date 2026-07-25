package com.hmdp.ai.application.agent;

import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.common.ErrorCode;
import com.hmdp.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class AgentRunAccessPolicy {

    public void requireRead(AiSecurityContext context, AgentRunRecord run) {
        if (context.getUserId().equals(run.getUserId())) {
            return;
        }
        if (context.getAuthorization().has(AiPermission.ADMIN)
                || context.getAuthorization().has(AiPermission.RUN_INSPECT)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
