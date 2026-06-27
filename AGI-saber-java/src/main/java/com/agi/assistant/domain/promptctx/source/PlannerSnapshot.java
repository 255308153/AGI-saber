package com.agi.assistant.domain.promptctx.source;

/**
 * Planner 当前状态的只读视图（对应 Go promptctx.PlannerSnapshot）。
 *
 * agent 包通过 PlannerProvider 暴露 TaskState 的快照，避免 promptctx 反向依赖 application。
 */
public class PlannerSnapshot {
    private String taskId;
    private String query;
    private String status;
    private String phase;
    private int totalSteps;
    private int currentStep;
    private int interruptedAt;
    private String nextStepName;
    private String nextStepTool;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }
    public int getCurrentStep() { return currentStep; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public int getInterruptedAt() { return interruptedAt; }
    public void setInterruptedAt(int interruptedAt) { this.interruptedAt = interruptedAt; }
    public String getNextStepName() { return nextStepName; }
    public void setNextStepName(String nextStepName) { this.nextStepName = nextStepName; }
    public String getNextStepTool() { return nextStepTool; }
    public void setNextStepTool(String nextStepTool) { this.nextStepTool = nextStepTool; }
}
