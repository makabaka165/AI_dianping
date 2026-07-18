package com.hmdp.ai.domain.knowledge;

public final class StoredObject {
    private final String objectKey,bucket,contentType,sha256,originalFileName;private final long size;
    public StoredObject(String objectKey,String bucket,String contentType,long size,String sha256,String originalFileName){this.objectKey=objectKey;this.bucket=bucket;this.contentType=contentType;this.size=size;this.sha256=sha256;this.originalFileName=originalFileName;}
    public String getObjectKey(){return objectKey;}public String getBucket(){return bucket;}public String getContentType(){return contentType;}public long getSize(){return size;}public String getSha256(){return sha256;}public String getOriginalFileName(){return originalFileName;}
}
