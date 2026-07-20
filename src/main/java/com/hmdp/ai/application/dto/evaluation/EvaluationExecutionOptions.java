package com.hmdp.ai.application.dto.evaluation;

public class EvaluationExecutionOptions {
    private boolean fakeModel;
    private boolean captureTrace = true;

    public boolean isFakeModel() { return fakeModel; }
    public void setFakeModel(boolean fakeModel) { this.fakeModel = fakeModel; }
    public boolean isCaptureTrace() { return captureTrace; }
    public void setCaptureTrace(boolean captureTrace) { this.captureTrace = captureTrace; }
}
