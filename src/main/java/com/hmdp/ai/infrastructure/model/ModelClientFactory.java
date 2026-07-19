package com.hmdp.ai.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class ModelClientFactory {
    private final SecretResolutionService secrets;
    private final ObjectMapper mapper;

    public ModelClientFactory(SecretResolutionService secrets, ObjectMapper mapper) {
        this.secrets = secrets;
        this.mapper = mapper;
    }

    public ModelClient create(ModelProfileVersion profile) {
        String provider = profile.getProvider().toUpperCase(java.util.Locale.ROOT);
        if (!provider.equals("OPENAI_COMPATIBLE") && !provider.equals("OPENAI")
                && !provider.equals("DASHSCOPE")) {
            throw new IllegalArgumentException("MODEL_PROVIDER_UNSUPPORTED");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(profile.getTimeoutMs(), 10_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new OpenAiCompatibleModelAdapter(profile, secrets, mapper, client);
    }
}
