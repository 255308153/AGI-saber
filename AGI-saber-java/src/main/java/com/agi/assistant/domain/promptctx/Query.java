package com.agi.assistant.domain.promptctx;

import java.util.List;

/**
 * 装配一次上下文时的输入快照（对应 Go promptctx.Query）。
 */
public class Query {
    private String text;
    private List<Double> embedding;
    private String taskId;
    /** chat / tool / react / rag */
    private String mode;

    public Query() {}

    public Query(String text, List<Double> embedding, String taskId, String mode) {
        this.text = text; this.embedding = embedding; this.taskId = taskId; this.mode = mode;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
