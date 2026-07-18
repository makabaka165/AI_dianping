package com.hmdp.ai.application.dto.agent;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class AgentInputRequest {
    @NotBlank @Size(max = 8000) private String text;
    @NotNull @Size(max = 32) private List<@Valid InputPartRequest> parts = new ArrayList<>();
    @NotNull @Size(max = 16) private List<@Valid AttachmentRequest> attachments = new ArrayList<>();
    @NotNull @Size(max = 32) private List<@Size(max = 2048) String> referenceUris = new ArrayList<>();

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<InputPartRequest> getParts() { return parts; }
    public void setParts(List<InputPartRequest> parts) { this.parts = parts; }
    public List<AttachmentRequest> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentRequest> attachments) { this.attachments = attachments; }
    public List<String> getReferenceUris() { return referenceUris; }
    public void setReferenceUris(List<String> referenceUris) { this.referenceUris = referenceUris; }
}
