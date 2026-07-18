package com.hmdp.ai.application.dto.agent;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AgentRollbackRequest {
    @NotBlank @Size(max = 1000) private String changeNote;

    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
}
