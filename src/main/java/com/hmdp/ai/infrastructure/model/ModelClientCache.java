package com.hmdp.ai.infrastructure.model;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Component
public class ModelClientCache {
    private final ModelClientFactory factory;
    private final Cache<String, ModelClient> clients = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    public ModelClientCache(ModelClientFactory factory) {
        this.factory = factory;
    }

    public ModelClient get(ModelProfileVersion profile) {
        return clients.get(key(profile), ignored -> factory.create(profile));
    }

    String key(ModelProfileVersion profile) {
        return profile.getProvider() + '\u001f' + profile.getBaseUrl() + '\u001f' + profile.getModelName()
                + '\u001f' + profile.getSecretRef() + '\u001f' + sha256(profile.getDefaultParametersJson());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("model client cache key cannot be generated", e);
        }
    }
}
