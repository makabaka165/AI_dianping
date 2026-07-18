package com.hmdp.ai.domain.evaluation;
import com.fasterxml.jackson.databind.JsonNode;
public final class EvaluationCandidate {private final JsonNode actual;private final long latencyMs,inputTokens,outputTokens;
    private final int modelCalls,toolCalls;private final double cost;private final boolean success;
    public EvaluationCandidate(JsonNode actual,long latencyMs,long inputTokens,long outputTokens,int modelCalls,int toolCalls,double cost,boolean success){
        this.actual=actual;this.latencyMs=latencyMs;this.inputTokens=inputTokens;this.outputTokens=outputTokens;this.modelCalls=modelCalls;this.toolCalls=toolCalls;this.cost=cost;this.success=success;}
    public JsonNode getActual(){return actual;}public long getLatencyMs(){return latencyMs;}public long getInputTokens(){return inputTokens;}
    public long getOutputTokens(){return outputTokens;}public int getModelCalls(){return modelCalls;}public int getToolCalls(){return toolCalls;}
    public double getCost(){return cost;}public boolean isSuccess(){return success;}}
