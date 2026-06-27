package com.agi.assistant.domain.promptctx.source;

import java.time.LocalDateTime;

/**
 * 任务执行过程中单步工具观察的快照（对应 Go promptctx.StepObservation）。
 */
public class StepObservation {
    private int stepId;
    private String toolName;
    private String result;
    private String error;
    private boolean success;
    private LocalDateTime createdAt;

    public StepObservation() { this.createdAt = LocalDateTime.now(); }

    public StepObservation(int stepId, String toolName, String result, boolean success) {
        this.stepId = stepId; this.toolName = toolName;
        this.result = result; this.success = success;
        this.createdAt = LocalDateTime.now();
    }

    public int getStepId() { return stepId; }
    public void setStepId(int stepId) { this.stepId = stepId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
