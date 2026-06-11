package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEvidence {
    private Long blogId;
    private Long shopId;
    private String snippet;
    private Integer liked;
    private LocalDateTime createdAt;
    private String matchedReason;
    private Double score;
}
