package com.hmdp.ai.domain.knowledge;
import java.util.List;
public interface RerankModelGateway {List<Double> rerank(String tenantId,String workspaceId,String modelProfileId,String query,List<String> documents);}
