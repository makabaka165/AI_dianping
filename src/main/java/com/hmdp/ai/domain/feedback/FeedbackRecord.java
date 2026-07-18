package com.hmdp.ai.domain.feedback;

import java.time.Instant;
import java.util.List;

public final class FeedbackRecord {
    private final String id,tenantId,workspaceId,runId,messageId,nodeRunId,comment,correctedAnswer,reviewStatus,status,createdBy;
    private final Integer rating; private final List<FeedbackTag> tags; private final Instant createdAt;
    public FeedbackRecord(String id,String tenantId,String workspaceId,String runId,String messageId,String nodeRunId,
                          Integer rating,List<FeedbackTag> tags,String comment,String correctedAnswer,String reviewStatus,
                          String status,String createdBy,Instant createdAt){this.id=id;this.tenantId=tenantId;
        this.workspaceId=workspaceId;this.runId=runId;this.messageId=messageId;this.nodeRunId=nodeRunId;
        this.rating=rating;this.tags=tags;this.comment=comment;this.correctedAnswer=correctedAnswer;
        this.reviewStatus=reviewStatus;this.status=status;this.createdBy=createdBy;this.createdAt=createdAt;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getRunId(){return runId;}
    public String getMessageId(){return messageId;} public String getNodeRunId(){return nodeRunId;}
    public Integer getRating(){return rating;} public List<FeedbackTag> getTags(){return tags;}
    public String getComment(){return comment;} public String getCorrectedAnswer(){return correctedAnswer;}
    public String getReviewStatus(){return reviewStatus;} public String getStatus(){return status;}
    public String getCreatedBy(){return createdBy;} public Instant getCreatedAt(){return createdAt;}
}
