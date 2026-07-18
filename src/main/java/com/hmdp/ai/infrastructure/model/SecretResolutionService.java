package com.hmdp.ai.infrastructure.model;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SecretResolutionService {
    private final List<SecretResolver> resolvers;

    public SecretResolutionService(List<SecretResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public String resolve(String reference) {
        return resolvers.stream()
                .filter(resolver -> resolver.supports(reference))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported secret reference"))
                .resolve(reference);
    }
}
