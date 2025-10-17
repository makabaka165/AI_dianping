package com.hmdp.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BlogAnalysis {
    private Long blogId;
    private String content;
    private String sentiment;  // 情感分析结果
    private List<String> keywords; // 关键词
    private Integer likedCount;
}
