package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_ai_document")
public class AiDocument {

    @TableId
    private String id;
    private String title;
    private String source;
    private String fileType;
    private String status;
    private Double qualityScore;
    private String qualityProfile;
    private String qualityLevel;
    private Long wordCount;
    private String keywords;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
