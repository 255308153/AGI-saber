package com.agi.assistant.domain.promptctx;

import java.util.List;

/**
 * 某类认知槽位的数据提供者（对应 Go promptctx.ContextSource）。
 *
 * 一个 source 可声明支持多个 SlotKind（例如 Profile source 可同时填 Profile/Recall）。
 */
public interface ContextSource {
    String id();

    boolean supports(SlotKind kind);

    /**
     * 在不超过 slot.filter.tokenBudget 的前提下，返回适合该槽位的 ContextItem。
     * 实现需自己做 TopK 截断与 budget 裁剪。
     */
    List<ContextItem> fetch(Slot slot, Query q);
}
