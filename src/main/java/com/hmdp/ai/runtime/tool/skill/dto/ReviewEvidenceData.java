package com.hmdp.ai.runtime.tool.skill.dto;
import lombok.Value;import java.time.LocalDateTime;
@Value public class ReviewEvidenceData {Long reviewId;String title;String content;Integer liked;LocalDateTime createdAt;}
