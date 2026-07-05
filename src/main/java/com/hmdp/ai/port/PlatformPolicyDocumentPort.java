package com.hmdp.ai.port;

import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.entity.DocumentMetadata;
import dev.langchain4j.data.document.Document;

import java.util.Set;

public interface PlatformPolicyDocumentPort {

    void saveImportedDocument(DocumentMetadata metadata,
                              DocumentQualityAssessment qualityAssessment,
                              Document document);

    boolean isActiveDocumentChunk(String documentId, String contentHash);

    void archiveMissingImportedDocuments(Set<String> activeDocumentIds);
}
