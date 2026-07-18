package com.hmdp.ai.application.dto.knowledge;
import javax.validation.constraints.*;
public class KnowledgeSearchRequest {@NotBlank @Size(max=4000) private String query;@Min(1) private Integer knowledgeBaseVersion;@Min(1) @Max(50) private Integer topK;public String getQuery(){return query;}public void setQuery(String v){query=v;}public Integer getKnowledgeBaseVersion(){return knowledgeBaseVersion;}public void setKnowledgeBaseVersion(Integer v){knowledgeBaseVersion=v;}public Integer getTopK(){return topK;}public void setTopK(Integer v){topK=v;}}
