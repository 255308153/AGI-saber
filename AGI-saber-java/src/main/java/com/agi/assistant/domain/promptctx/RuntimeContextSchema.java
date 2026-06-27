package com.agi.assistant.domain.promptctx;

import java.util.List;

/**
 * 某个 Mode 下需要装配的认知槽位与顺序（对应 Go promptctx.RuntimeContextSchema）。
 * 槽位顺序即 Render 时的输出顺序。
 */
public class RuntimeContextSchema {
    private final String mode;
    private final List<Slot> slots;

    public RuntimeContextSchema(String mode, List<Slot> slots) {
        this.mode = mode; this.slots = slots;
    }

    public String getMode() { return mode; }
    public List<Slot> getSlots() { return slots; }
}
