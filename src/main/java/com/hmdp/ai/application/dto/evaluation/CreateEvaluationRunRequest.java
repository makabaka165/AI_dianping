package com.hmdp.ai.application.dto.evaluation;
import javax.validation.Valid;import javax.validation.constraints.*;import java.util.*;
public class CreateEvaluationRunRequest {@NotBlank@Size(max=64)private String datasetId;@NotBlank@Size(max=32)private String targetType;
    @Size(max=64)private String targetId;@Min(1)private Integer targetVersion;@Valid private EvaluationExecutionOptions executionOptions=new EvaluationExecutionOptions();@Size(max=1000)private List<@Valid EvaluationCandidateRequest>candidates=new ArrayList<>();
    public String getDatasetId(){return datasetId;}public void setDatasetId(String v){datasetId=v;}public String getTargetType(){return targetType;}public void setTargetType(String v){targetType=v;}
    public String getTargetId(){return targetId;}public void setTargetId(String v){targetId=v;}public Integer getTargetVersion(){return targetVersion;}public void setTargetVersion(Integer v){targetVersion=v;}
    public EvaluationExecutionOptions getExecutionOptions(){return executionOptions;}public void setExecutionOptions(EvaluationExecutionOptions v){executionOptions=v;}
    public List<EvaluationCandidateRequest>getCandidates(){return candidates;}public void setCandidates(List<EvaluationCandidateRequest>v){candidates=v;}}
