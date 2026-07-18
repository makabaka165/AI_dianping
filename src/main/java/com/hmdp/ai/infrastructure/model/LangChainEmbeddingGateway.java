package com.hmdp.ai.infrastructure.model;

import com.hmdp.ai.domain.knowledge.EmbeddingGateway;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelType;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class LangChainEmbeddingGateway implements EmbeddingGateway {
    private final ModelProfileRepository profiles;private final SecretResolver secrets;
    public LangChainEmbeddingGateway(ModelProfileRepository profiles,SecretResolver secrets){this.profiles=profiles;this.secrets=secrets;}
    @Override public List<float[]> embed(String tenant,String workspace,String profileId,List<String> texts,int dimension){ModelProfile profile=profiles.findById(tenant,workspace,profileId).orElseThrow(()->new IllegalStateException("EMBEDDING_MODEL_NOT_FOUND"));if(profile.getModelType()!=ModelType.EMBEDDING||!profile.isEnabled())throw new IllegalStateException("EMBEDDING_MODEL_DISABLED");String secret=secrets.resolve(profile.getSecretRef());if(secret==null||secret.trim().isEmpty())throw new IllegalStateException("EMBEDDING_PROVIDER_NOT_CONFIGURED");EmbeddingModel model=OpenAiEmbeddingModel.builder().apiKey(secret).baseUrl(profile.getBaseUrl()).modelName(profile.getModelName()).timeout(Duration.ofMillis(profile.getTimeoutMs())).build();List<float[]> values=new ArrayList<>();for(String text:texts){float[] vector=model.embed(text).content().vector();if(vector.length!=dimension)throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");values.add(vector);}return values;}
}
