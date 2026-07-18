package com.hmdp.ai.infrastructure.model;

import com.hmdp.ai.domain.model.ModelHealthChecker;
import com.hmdp.ai.domain.model.ModelHealthResult;
import com.hmdp.ai.domain.model.ModelProfile;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
public class ModelProfileHealthChecker implements ModelHealthChecker {
    private final SecretResolutionService secrets;

    public ModelProfileHealthChecker(SecretResolutionService secrets) {
        this.secrets = secrets;
    }

    @Override
    public ModelHealthResult check(ModelProfile profile) {
        long started = System.nanoTime();
        try {
            String secret = secrets.resolve(profile.getSecretRef());
            URI base = URI.create(profile.getBaseUrl());
            String path = base.getPath() == null ? "" : base.getPath();
            String endpoint = profile.getBaseUrl().replaceAll("/+$", "")
                    + (path.endsWith("/models") ? "" : "/models");
            int timeout = Math.max(500, Math.min(profile.getTimeoutMs(), 60000));
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .readTimeout(Duration.ofMillis(timeout))
                    .callTimeout(Duration.ofMillis(timeout))
                    .followRedirects(false)
                    .build();
            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + secret)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                long latency = elapsedMillis(started);
                if (response.isSuccessful()) {
                    return new ModelHealthResult(profile.getId(), "UP", latency, null);
                }
                return new ModelHealthResult(profile.getId(), "DOWN", latency,
                        "MODEL_HTTP_" + response.code());
            }
        } catch (SecretNotConfiguredException e) {
            return new ModelHealthResult(profile.getId(), "DOWN", elapsedMillis(started),
                    "PROVIDER_NOT_CONFIGURED");
        } catch (Exception e) {
            return new ModelHealthResult(profile.getId(), "DOWN", elapsedMillis(started),
                    "MODEL_HEALTH_CHECK_FAILED");
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
