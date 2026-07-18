package com.hmdp.ai.application.security;

import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.application.security.AiSecurityContextHolder;
import com.hmdp.common.ErrorCode;
import com.hmdp.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class AiAccessGuard {

    public AiSecurityContext require(AiPermission permission) {
        AiSecurityContext context = AiSecurityContextHolder.require();
        if (!context.getAuthorization().has(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return context;
    }
}
