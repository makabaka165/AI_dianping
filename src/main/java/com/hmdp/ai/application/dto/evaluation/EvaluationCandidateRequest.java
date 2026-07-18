package com.hmdp.ai.application.dto.evaluation;
import com.fasterxml.jackson.databind.JsonNode;import javax.validation.constraints.*;
public class EvaluationCandidateRequest {@NotBlank@Size(max=64)private String caseId;@NotNull private JsonNode actual;
    @PositiveOrZero private long latencyMs;@PositiveOrZero private long inputTokens;@PositiveOrZero private long outputTokens;
    @PositiveOrZero private int modelCalls;@PositiveOrZero private int toolCalls;@PositiveOrZero private double cost;private boolean success=true;
    public String getCaseId(){return caseId;}public void setCaseId(String v){caseId=v;}public JsonNode getActual(){return actual;}public void setActual(JsonNode v){actual=v;}
    public long getLatencyMs(){return latencyMs;}public void setLatencyMs(long v){latencyMs=v;}public long getInputTokens(){return inputTokens;}public void setInputTokens(long v){inputTokens=v;}
    public long getOutputTokens(){return outputTokens;}public void setOutputTokens(long v){outputTokens=v;}public int getModelCalls(){return modelCalls;}public void setModelCalls(int v){modelCalls=v;}
    public int getToolCalls(){return toolCalls;}public void setToolCalls(int v){toolCalls=v;}public double getCost(){return cost;}public void setCost(double v){cost=v;}
    public boolean isSuccess(){return success;}public void setSuccess(boolean v){success=v;}}
