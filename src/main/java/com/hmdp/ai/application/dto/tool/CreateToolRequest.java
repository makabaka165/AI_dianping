package com.hmdp.ai.application.dto.tool;

import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import javax.validation.constraints.*;import java.util.ArrayList;import java.util.List;

public class CreateToolRequest {
    @NotBlank @Size(max=128) @Pattern(regexp="[a-z][a-z0-9-]*") private String code;
    @NotBlank @Size(max=128) private String name; @NotBlank @Size(max=1000) private String description;
    @NotNull private ToolProtocol protocol; @NotBlank @Size(max=200000) private String inputSchema;
    @NotBlank @Size(max=200000) private String outputSchema; @NotNull private ToolRiskLevel riskLevel;
    private boolean sideEffect; private boolean idempotent=true; @Min(1) @Max(600000) private int timeoutMs=30000;
    @NotBlank @Size(max=200000) private String retryPolicyJson="{\"maxAttempts\":1}";
    @Size(max=32) private List<@NotNull AiPermission> requiredPermissions=new ArrayList<>();
    @NotBlank @Size(max=200000) private String configurationJson="{}"; private boolean enabled=true;
    @NotBlank @Size(max=1000) private String changeNote;
    public String getCode(){return code;}public void setCode(String v){code=v;}public String getName(){return name;}public void setName(String v){name=v;}public String getDescription(){return description;}public void setDescription(String v){description=v;}public ToolProtocol getProtocol(){return protocol;}public void setProtocol(ToolProtocol v){protocol=v;}public String getInputSchema(){return inputSchema;}public void setInputSchema(String v){inputSchema=v;}public String getOutputSchema(){return outputSchema;}public void setOutputSchema(String v){outputSchema=v;}public ToolRiskLevel getRiskLevel(){return riskLevel;}public void setRiskLevel(ToolRiskLevel v){riskLevel=v;}public boolean isSideEffect(){return sideEffect;}public void setSideEffect(boolean v){sideEffect=v;}public boolean isIdempotent(){return idempotent;}public void setIdempotent(boolean v){idempotent=v;}public int getTimeoutMs(){return timeoutMs;}public void setTimeoutMs(int v){timeoutMs=v;}public String getRetryPolicyJson(){return retryPolicyJson;}public void setRetryPolicyJson(String v){retryPolicyJson=v;}public List<AiPermission> getRequiredPermissions(){return requiredPermissions;}public void setRequiredPermissions(List<AiPermission> v){requiredPermissions=v;}public String getConfigurationJson(){return configurationJson;}public void setConfigurationJson(String v){configurationJson=v;}public boolean isEnabled(){return enabled;}public void setEnabled(boolean v){enabled=v;}public String getChangeNote(){return changeNote;}public void setChangeNote(String v){changeNote=v;}
}
