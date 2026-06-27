package com.agi.assistant.domain.promptctx.source;

import java.time.LocalDateTime;

/**
 * 单次工具调用的简要记录（对应 Go promptctx.ToolCallTrace）。
 */
public class ToolCallTrace {
    private String toolName;
    private boolean success;
    /** 截断后的结果或错误摘要 */
    private String summary;
    private LocalDateTime createdAt;

    public ToolCallTrace() {}

    public ToolCallTrace(String toolName, boolean success, String summary) {
        this.toolName = toolName; this.success = success; this.summary = summary;
        this.createdAt = LocalDateTime.now();
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
