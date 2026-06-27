package com.agi.assistant.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆项。
 *
 * <p>新增字段（与 Go domain/memory/longterm.Item 对齐，用于支持 LLM 分类
 * 与按类别召回）：</p>
 * <ul>
 *   <li>{@code category} —— 主类别：identity / preference / tool_failure / policy / general（默认 general）</li>
 *   <li>{@code tags} —— 自由标签</li>
 *   <li>{@code slotHint} —— 提示装配器把这条记忆优先填到哪个 SlotKind</li>
 * </ul>
 *
 * <p>构造函数 {@code MemoryItem(int, String, double, List)} 保持向后兼容，
 * 新字段一律走 setter。</p>
 */
public class MemoryItem {
    private int id;
    private String content;
    private double importance;
    private List<Double> embedding;
    private double score;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessed;

    /** 主类别（identity / preference / tool_failure / policy / general），默认 general */
    private String category = "general";
    /** 自由标签 */
    private List<String> tags = new ArrayList<>();
    /** SlotKind 提示，可为空 */
    private String slotHint;

    public MemoryItem() {}
    public MemoryItem(int id, String content, double importance, List<Double> embedding) {
        this.id = id; this.content = content; this.importance = importance; this.embedding = embedding;
        this.createdAt = LocalDateTime.now(); this.lastAccessed = LocalDateTime.now();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }
    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastAccessed() { return lastAccessed; }
    public void setLastAccessed(LocalDateTime lastAccessed) { this.lastAccessed = lastAccessed; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null || category.isEmpty() ? "general" : category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public String getSlotHint() { return slotHint; }
    public void setSlotHint(String slotHint) { this.slotHint = slotHint; }
}
