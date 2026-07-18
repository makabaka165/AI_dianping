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
        if (!context.getUserId().equals(run.getUserId())
                && !context.getAuthorization().has(AiPermission.ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
