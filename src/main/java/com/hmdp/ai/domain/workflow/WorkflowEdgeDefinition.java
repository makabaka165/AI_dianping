package com.hmdp.ai.domain.workflow;

public final class WorkflowEdgeDefinition {
    private final String id;
    private final String sourceNodeCode;
    private final String targetNodeCode;
    private final String conditionJson;
    private final int priority;
    private final String label;

    public WorkflowEdgeDefinition(String id, String sourceNodeCode, String targetNodeCode,
                                  String conditionJson, int priority, String label) {
        this.id=id; this.sourceNodeCode=sourceNodeCode; this.targetNodeCode=targetNodeCode;
        this.conditionJson=conditionJson; this.priority=priority; this.label=label;
    }
    public String getId(){return id;} public String getSourceNodeCode(){return sourceNodeCode;}
    public String getTargetNodeCode(){return targetNodeCode;} public String getConditionJson(){return conditionJson;}
    public int getPriority(){return priority;} public String getLabel(){return label;}
}
