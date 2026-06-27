package com.agi.assistant.domain.promptctx;

/**
 * Schema 中的单个认知槽位定义（对应 Go promptctx.Slot）。
 */
public class Slot {
    private final SlotKind kind;
    /** Required 槽位即使为空也会渲染占位 */
    private final boolean required;
    /** 传给 ContextSource 的过滤参数 */
    private final SlotFilter filter;
    /** 留空时使用 kind */
    private final String template;

    public Slot(SlotKind kind, boolean required, SlotFilter filter) {
        this(kind, required, filter, "");
    }

    public Slot(SlotKind kind, boolean required, SlotFilter filter, String template) {
        this.kind = kind;
        this.required = required;
        this.filter = filter != null ? filter : new SlotFilter();
        this.template = template == null ? "" : template;
    }

    public SlotKind getKind() { return kind; }
    public boolean isRequired() { return required; }
    public SlotFilter getFilter() { return filter; }
    public String getTemplate() { return template; }
}
