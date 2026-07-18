package com.hmdp.ai.application.dto.agent;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AttachmentRequest {
    @NotBlank @Size(max = 64) private String attachmentId;
    @NotBlank @Size(max = 255) private String name;
    @NotBlank @Size(max = 128) private String contentType;
    @Min(0) @Max(20971520) private long sizeBytes;
    @NotBlank @Size(max = 2048) private String uri;

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
}
