package com.agi.assistant.domain.promptctx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条已装入槽位的内容（对应 Go promptctx.ContextItem）。
 */
public class ContextItem {
    private String text;
    private double score;
    /** 调试用：标记来自哪个 ContextSource */
    private String source;
    /** 调试用元数据 */
    private Map<String, String> meta = new LinkedHashMap<>();

    public ContextItem() {}

    public ContextItem(String text) { this.text = text; }

    public ContextItem(String text, double score, String source) {
        this.text = text; this.score = score; this.source = source;
    }

    public ContextItem(String text, double score, String source, Map<String, String> meta) {
        this(text, score, source);
        this.meta = meta != null ? meta : new LinkedHashMap<>();
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Map<String, String> getMeta() { return meta; }
    public void setMeta(Map<String, String> meta) { this.meta = meta; }
}
