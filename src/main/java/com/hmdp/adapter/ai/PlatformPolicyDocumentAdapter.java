package com.hmdp.adapter.ai;

import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.service.DocumentManagementService;
import dev.langchain4j.data.document.Document;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Component
public class PlatformPolicyDocumentAdapter implements PlatformPolicyDocumentPort {

    @Resource
    private DocumentManagementService documentManagementService;

    @Override
    public void saveImportedDocument(String title,
                                     String source,
                                     String fileType,
                                     LocalDateTime createdAt,
                                     LocalDateTime updatedAt,
                                     double qualityScore,
                                     int wordCount,
                                     Document document) {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setTitle(title);
        metadata.setSource(source);
        metadata.setFileType(fileType);
        metadata.setCreatedAt(createdAt);
        metadata.setUpdatedAt(updatedAt);
        metadata.setStatus(DocumentStatus.PUBLISHED);
        metadata.setQualityScore(qualityScore);
        metadata.setWordCount(wordCount);
        documentManagementService.saveDocument(metadata);
    }
}
