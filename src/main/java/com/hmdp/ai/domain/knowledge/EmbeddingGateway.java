package com.hmdp.ai.domain.knowledge;

import java.util.List;

public interface EmbeddingGateway {
    List<float[]> embed(String tenantId,String workspaceId,String modelProfileId,List<String> texts,int expectedDimension);
}
