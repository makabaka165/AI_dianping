package com.hmdp.ai.application.dto.feedback;
import com.hmdp.ai.domain.feedback.FeedbackTag;import javax.validation.constraints.*;import java.util.*;
public class CreateFeedbackRequest {@NotBlank @Size(max=64)private String runId;@Size(max=64)private String messageId;
    @Size(max=64)private String nodeRunId;@Min(-1)@Max(1)private Integer rating;@NotNull @Size(max=10)private List<FeedbackTag>tags=new ArrayList<>();
    @Size(max=4000)private String comment;@Size(max=16000)private String correctedAnswer;
    public String getRunId(){return runId;}public void setRunId(String v){runId=v;}public String getMessageId(){return messageId;}
    public void setMessageId(String v){messageId=v;}public String getNodeRunId(){return nodeRunId;}public void setNodeRunId(String v){nodeRunId=v;}
    public Integer getRating(){return rating;}public void setRating(Integer v){rating=v;}public List<FeedbackTag>getTags(){return tags;}
    public void setTags(List<FeedbackTag>v){tags=v;}public String getComment(){return comment;}public void setComment(String v){comment=v;}
    public String getCorrectedAnswer(){return correctedAnswer;}public void setCorrectedAnswer(String v){correctedAnswer=v;}}
