package com.agi.assistant.domain.promptctx;

import java.util.ArrayList;
import java.util.List;

/**
 * 装配后的单个槽位结果（对应 Go promptctx.FilledSlot）。
 */
public class FilledSlot {
    private SlotKind kind;
    private List<ContextItem> items = new ArrayList<>();
    /** 因预算或无数据被跳过 */
    private boolean skipped;
    /** 跳过原因（debug） */
    private String reason;

    public FilledSlot() {}
    public FilledSlot(SlotKind kind) { this.kind = kind; }
    public FilledSlot(SlotKind kind, List<ContextItem> items) {
        this.kind = kind;
        this.items = items != null ? items : new ArrayList<>();
    }

    public SlotKind getKind() { return kind; }
    public void setKind(SlotKind kind) { this.kind = kind; }
    public List<ContextItem> getItems() { return items; }
    public void setItems(List<ContextItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
