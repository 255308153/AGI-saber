package com.agi.assistant.domain.graph;

/**
 * 任务图节点类型（对应 Go domain/graph/graph.go 的 NodeType）。
 *
 * <p>当前支持 {@link #TOOL} 与 {@link #SUB_AGENT}；{@link #THINK} / {@link #AGGREGATE}
 * 预留给后续推理 / 聚合节点。</p>
 */
public enum NodeType {
    TOOL("tool"),
    SUB_AGENT("sub_agent"),
    THINK("think"),
    AGGREGATE("aggregate");

    private final String value;
    NodeType(String value) { this.value = value; }
    public String value() { return value; }

    public static NodeType fromValue(String s) {
        if (s == null) return TOOL;
        for (NodeType nt : values()) if (nt.value.equals(s)) return nt;
        return TOOL;
    }

    @Override public String toString() { return value; }
}
