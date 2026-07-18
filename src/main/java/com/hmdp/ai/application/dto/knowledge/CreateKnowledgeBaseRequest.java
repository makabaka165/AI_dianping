package com.hmdp.ai.application.dto.knowledge;
import javax.validation.constraints.*;
public class CreateKnowledgeBaseRequest {@NotBlank @Size(max=64) @Pattern(regexp="[a-z][a-z0-9-]*") private String code;@NotBlank @Size(max=128) private String name;@Size(max=1000) private String description;public String getCode(){return code;}public void setCode(String v){code=v;}public String getName(){return name;}public void setName(String v){name=v;}public String getDescription(){return description;}public void setDescription(String v){description=v;}}
