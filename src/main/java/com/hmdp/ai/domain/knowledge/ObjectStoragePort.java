package com.hmdp.ai.domain.knowledge;

import java.io.InputStream;

public interface ObjectStoragePort {
    StoredObject put(String tenantId,String knowledgeBaseId,String fileName,String contentType,byte[] content,String sha256);
    InputStream get(String bucket,String objectKey);
    void delete(String bucket,String objectKey);
}
