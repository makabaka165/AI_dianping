package com.hmdp.ai.infrastructure.model;

public interface SecretResolver {
    boolean supports(String secretReference);

    String resolve(String secretReference);
}
