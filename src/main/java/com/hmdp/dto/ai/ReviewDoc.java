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
public class ReviewDoc {
    private Long id;
    private Long shopId;
    private String title;
    private String content;
    private Integer liked;
    private LocalDateTime createTime;
    private Integer status;
    private Integer deleted;
}
