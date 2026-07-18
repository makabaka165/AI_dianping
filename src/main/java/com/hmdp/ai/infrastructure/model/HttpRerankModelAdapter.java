package com.hmdp.ai.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.knowledge.RerankModelGateway;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelType;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class HttpRerankModelAdapter implements RerankModelGateway {
    private final ModelProfileRepository profiles;
    private final SecretResolver secrets;
    private final ObjectMapper mapper;

    public HttpRerankModelAdapter(ModelProfileRepository profiles, SecretResolver secrets, ObjectMapper mapper) {
        this.profiles = profiles;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    @Override
    public List<Double> rerank(String tenant, String workspace, String id, String query,
                               List<String> documents) {
        try {
            ModelProfile profile = profiles.findById(tenant, workspace, id)
                    .orElseThrow(() -> new IllegalStateException("RERANK_MODEL_NOT_FOUND"));
            if (profile.getModelType() != ModelType.RERANK || !profile.isEnabled()) {
                throw new IllegalStateException("RERANK_MODEL_DISABLED");
            }
            String secret = secrets.resolve(profile.getSecretRef());
            if (secret == null || secret.trim().isEmpty()) {
                throw new IllegalStateException("RERANK_PROVIDER_NOT_CONFIGURED");
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("model", profile.getModelName());
            body.put("query", query);
            ArrayNode docs = body.putArray("documents");
            documents.forEach(docs::add);
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofMillis(profile.getTimeoutMs()))
                    .readTimeout(Duration.ofMillis(profile.getTimeoutMs())).build();
            Request request = new Request.Builder().url(profile.getBaseUrl())
                    .header("Authorization", "Bearer " + secret)
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("RERANK_HTTP_" + response.code());
                }
                JsonNode json = mapper.readTree(response.body().bytes());
                List<Double> scores = new ArrayList<>(Collections.nCopies(documents.size(), 0.0));
                for (JsonNode item : json.path("results")) {
                    int index = item.path("index").asInt(-1);
                    if (index >= 0 && index < scores.size()) {
                        scores.set(index, item.path("relevance_score").asDouble());
                    }
                }
                return scores;
            }
        } catch (Exception e) {
            throw new IllegalStateException("RERANK_EXECUTION_FAILED", e);
        }
    }
}
