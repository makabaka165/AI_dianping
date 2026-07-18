package com.hmdp.ai.application.dto.prompt;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class CreatePromptRequest {
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,63}") private String code;
    @NotBlank @Size(max = 128) private String name;
    @Size(max = 1000) private String description;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
