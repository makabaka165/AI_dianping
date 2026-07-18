package com.hmdp.ai.domain.workflow;

public final class WorkflowNodeDefinition {
    private final String id;
    private final String code;
    private final WorkflowNodeType type;
    private final String name;
    private final String configurationJson;
    private final String inputMappingJson;
    private final String outputMappingJson;
    private final int timeoutMs;
    private final int maxAttempts;

    public WorkflowNodeDefinition(String id, String code, WorkflowNodeType type, String name,
                                  String configurationJson, String inputMappingJson, String outputMappingJson,
                                  int timeoutMs, int maxAttempts) {
        this.id = id; this.code = code; this.type = type; this.name = name;
        this.configurationJson = configurationJson; this.inputMappingJson = inputMappingJson;
        this.outputMappingJson = outputMappingJson; this.timeoutMs = timeoutMs; this.maxAttempts = maxAttempts;
    }
    public String getId(){return id;} public String getCode(){return code;} public WorkflowNodeType getType(){return type;}
    public String getName(){return name;} public String getConfigurationJson(){return configurationJson;}
    public String getInputMappingJson(){return inputMappingJson;} public String getOutputMappingJson(){return outputMappingJson;}
    public int getTimeoutMs(){return timeoutMs;} public int getMaxAttempts(){return maxAttempts;}
}
