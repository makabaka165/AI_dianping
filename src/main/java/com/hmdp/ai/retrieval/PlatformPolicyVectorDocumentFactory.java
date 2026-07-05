package com.hmdp.ai.retrieval;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PlatformPolicyVectorDocumentFactory {

    public static final String META_DOCUMENT_ID = "documentId";
    public static final String META_CONTENT_HASH = "contentHash";
    public static final String META_STATUS = "status";
    public static final String META_TITLE = "title";
    public static final String META_SOURCE = "source";
    public static final String META_FILE_TYPE = "fileType";

    private PlatformPolicyVectorDocumentFactory() {
    }

    public static Document toDocument(String documentId, Document document, DocumentMetadata metadata) {
        String content = document == null ? "" : document.text();
        return Document.from(content, metadata(documentId, metadata, content));
    }

    public static Metadata metadata(String documentId, DocumentMetadata metadata, String content) {
        String safeDocumentId = safe(documentId);
        return new Metadata()
                .put(META_DOCUMENT_ID, safeDocumentId)
                .put(META_CONTENT_HASH, contentHash(safeDocumentId, content))
                .put(META_STATUS, statusName(metadata))
                .put(META_TITLE, metadata == null || metadata.getTitle() == null ? "" : metadata.getTitle())
                .put(META_SOURCE, metadata == null || metadata.getSource() == null ? "" : metadata.getSource())
                .put(META_FILE_TYPE, metadata == null || metadata.getFileType() == null ? "" : metadata.getFileType());
    }

    public static String contentHash(String documentId, String content) {
        return sha256(safe(documentId) + "|" + safe(content));
    }

    public static String importedDocumentId(String sourceKey) {
        return "imported-document-" + sha256(safe(sourceKey)).substring(0, 16);
    }

    private static String statusName(DocumentMetadata metadata) {
        DocumentStatus status = metadata == null || metadata.getStatus() == null
                ? DocumentStatus.PUBLISHED
                : metadata.getStatus();
        return status.name();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
