package com.agi.assistant.domain.rag;

/**
 * Rewriter 看到的对话历史最小结构（对应 Go domain/rag.HistoryMessage）。
 * 抽出来避免 rag 包反向依赖 memory 包。
 */
public class HistoryMessage {
    private String role;     // "user" | "assistant"
    private String content;

    public HistoryMessage() {}
    public HistoryMessage(String role, String content) {
        this.role = role; this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
