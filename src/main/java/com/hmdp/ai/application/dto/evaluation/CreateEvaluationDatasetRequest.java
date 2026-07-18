package com.hmdp.ai.application.dto.evaluation;
import com.hmdp.ai.domain.evaluation.EvaluationType;import javax.validation.constraints.*;
public class CreateEvaluationDatasetRequest {@NotBlank@Pattern(regexp="[a-z0-9][a-z0-9_-]{1,63}")private String code;
    @NotBlank@Size(max=128)private String name;@Size(max=1000)private String description;@NotNull private EvaluationType type;
    public String getCode(){return code;}public void setCode(String v){code=v;}public String getName(){return name;}public void setName(String v){name=v;}
    public String getDescription(){return description;}public void setDescription(String v){description=v;}public EvaluationType getType(){return type;}public void setType(EvaluationType v){type=v;}}
