package com.hmdp.ai.application.dto.memory;
import javax.validation.constraints.NotBlank;import javax.validation.constraints.Size;
public class UpdateMemoryFactRequest {@NotBlank @Size(max=2000) private String factValue;
    public String getFactValue(){return factValue;}public void setFactValue(String value){factValue=value;}}
