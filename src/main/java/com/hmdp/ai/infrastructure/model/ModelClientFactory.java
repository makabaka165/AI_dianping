package com.hmdp.ai.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class ModelClientFactory {
    private final SecretResolutionService secrets;
    private final ObjectMapper mapper;
    private final RunCancellationRegistry cancellations;

    public ModelClientFactory(SecretResolutionService secrets, ObjectMapper mapper) {
        this(secrets, mapper, null);
    }

    @Autowired
    public ModelClientFactory(SecretResolutionService secrets, ObjectMapper mapper,
                              RunCancellationRegistry cancellations) {
        this.secrets = secrets;
        this.mapper = mapper;
        this.cancellations = cancellations;
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
        return new OpenAiCompatibleModelAdapter(profile, secrets, mapper, client, cancellations);
    }
}
