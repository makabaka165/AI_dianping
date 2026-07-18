package com.hmdp.ai.domain.model;

public interface ModelHealthChecker {
    ModelHealthResult check(ModelProfile profile);
}
