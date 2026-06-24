package com.hmdp.entity;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {
    private String id;
    private String title;
    private String source;
    private String fileType; // txt, pdf, md等
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String version;
    private DocumentStatus status;
    private double qualityScore;
    private String qualityProfile;
    private String qualityLevel;
    private Map<String, Double> qualityDimensions;
    private List<String> qualityIssues;
    private List<String> qualitySuggestions;
    private long wordCount;
    private String[] keywords;
}
