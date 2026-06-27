package com.agi.assistant.domain.promptctx.source;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * 最近 N 次工具调用的环形缓冲（对应 Go promptctx.ToolStateTracker）。
 */
public class ToolStateTracker {

    private final Deque<ToolCallTrace> buf = new LinkedList<>();
    private final int max;

    public ToolStateTracker() { this(10); }
    public ToolStateTracker(int max) {
        this.max = max <= 0 ? 10 : max;
    }

    public synchronized void record(ToolCallTrace trace) {
        if (trace == null) return;
        if (trace.getCreatedAt() == null) trace.setCreatedAt(LocalDateTime.now());
        if (trace.getSummary() != null && trace.getSummary().length() > 120) {
            trace.setSummary(trace.getSummary().substring(0, 120) + "…");
        }
        buf.addLast(trace);
        while (buf.size() > max) buf.removeFirst();
    }

    public synchronized List<ToolCallTrace> snapshot() {
        return new ArrayList<>(buf);
    }
}
