package com.hmdp.ai.port;

import dev.langchain4j.data.document.Document;

import java.time.LocalDateTime;

public interface PlatformPolicyDocumentPort {

    void saveImportedDocument(String title,
                              String source,
                              String fileType,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              double qualityScore,
                              int wordCount,
                              Document document);
}
