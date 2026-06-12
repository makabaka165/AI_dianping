package com.hmdp.ai.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplateRender {
    private String content;
    private String version;
    private String variant;
}
