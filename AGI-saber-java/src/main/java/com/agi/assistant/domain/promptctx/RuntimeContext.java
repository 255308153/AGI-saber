package com.agi.assistant.domain.promptctx;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次装配的全部结果（对应 Go promptctx.RuntimeContext）。
 *
 * 通过 render 得到 System Prompt 前缀。
 */
public class RuntimeContext {
    private RuntimeContextSchema schema;
    private List<FilledSlot> filled = new ArrayList<>();

    public RuntimeContext() {}

    public RuntimeContext(RuntimeContextSchema schema, List<FilledSlot> filled) {
        this.schema = schema;
        this.filled = filled != null ? filled : new ArrayList<>();
    }

    public RuntimeContextSchema getSchema() { return schema; }
    public void setSchema(RuntimeContextSchema schema) { this.schema = schema; }
    public List<FilledSlot> getFilled() { return filled; }
    public void setFilled(List<FilledSlot> filled) {
        this.filled = filled != null ? filled : new ArrayList<>();
    }

    /** 取出特定槽位（不存在返回 null） */
    public FilledSlot slotByKind(SlotKind kind) {
        for (FilledSlot fs : filled) {
            if (fs.getKind() == kind) return fs;
        }
        return null;
    }

    /** 将所有非空槽位按 Schema 顺序渲染为 zh-CN 提示前缀 */
    public String render() {
        if (filled.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (FilledSlot fs : filled) {
            if (fs.isSkipped() || fs.getItems() == null || fs.getItems().isEmpty()) continue;
            String section = renderSlot(fs);
            if (section.isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(section);
        }
        return out.toString();
    }

    private static String renderSlot(FilledSlot fs) {
        StringBuilder out = new StringBuilder();
        boolean any = false;
        for (ContextItem item : fs.getItems()) {
            String text = item.getText();
            if (text == null || text.isBlank()) continue;
            if (any) out.append("\n");
            out.append("- ").append(text.trim());
            any = true;
        }
        if (!any) return "";
        return "【" + Schemas.slotTitle(fs.getKind()) + "】\n" + out;
    }
}
