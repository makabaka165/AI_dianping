package com.hmdp.ai.domain.run;

public final class AttachmentReference {
    private final String attachmentId;
    private final String name;
    private final String contentType;
    private final long sizeBytes;
    private final String uri;

    public AttachmentReference(String attachmentId, String name, String contentType, long sizeBytes, String uri) {
        this.attachmentId = attachmentId;
        this.name = name;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uri = uri;
    }

    public String getAttachmentId() { return attachmentId; }
    public String getName() { return name; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getUri() { return uri; }
}
