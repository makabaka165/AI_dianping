package com.hmdp.ai.application.dto.prompt;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class CreatePromptVersionRequest {
    @NotBlank @Size(max = 50000) private String systemPrompt;
    @NotBlank @Size(max = 50000) private String taskPrompt;
    @Size(max = 50000) private String toolInstruction;
    @Size(max = 50000) private String retrievalInstruction;
    @Size(max = 50000) private String outputInstruction;
    @NotBlank @Size(max = 50000) private String variablesSchema;
    @NotBlank @Size(max = 50000) private String inputSchema;
    @NotBlank @Size(max = 50000) private String outputSchema;
    @NotBlank @Size(max = 100000) private String examplesJson;
    @NotBlank @Size(max = 1000) private String changeNote;

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getTaskPrompt() { return taskPrompt; }
    public void setTaskPrompt(String taskPrompt) { this.taskPrompt = taskPrompt; }
    public String getToolInstruction() { return toolInstruction; }
    public void setToolInstruction(String toolInstruction) { this.toolInstruction = toolInstruction; }
    public String getRetrievalInstruction() { return retrievalInstruction; }
    public void setRetrievalInstruction(String retrievalInstruction) { this.retrievalInstruction = retrievalInstruction; }
    public String getOutputInstruction() { return outputInstruction; }
    public void setOutputInstruction(String outputInstruction) { this.outputInstruction = outputInstruction; }
    public String getVariablesSchema() { return variablesSchema; }
    public void setVariablesSchema(String variablesSchema) { this.variablesSchema = variablesSchema; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public void setOutputSchema(String outputSchema) { this.outputSchema = outputSchema; }
    public String getExamplesJson() { return examplesJson; }
    public void setExamplesJson(String examplesJson) { this.examplesJson = examplesJson; }
    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
}
