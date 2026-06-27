package com.agi.assistant.domain.promptctx.source;

import com.agi.assistant.domain.promptctx.ContextItem;
import com.agi.assistant.domain.promptctx.ContextSource;
import com.agi.assistant.domain.promptctx.Query;
import com.agi.assistant.domain.promptctx.Slot;
import com.agi.assistant.domain.promptctx.SlotKind;
import com.agi.assistant.model.MemoryItem;
import com.agi.assistant.service.memory.LongTermMemory;
import com.agi.assistant.service.memory.PreferenceMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 装填 Long-term Profile 槽位（对应 Go promptctx.ProfileSource）。
 *
 * 数据来源：
 *  - Preference（高优先级，稳定身份信息），按 key 字母序排序避免每轮 prompt 抖动
 *  - LTM 中类别命中 slot.filter.categories 的条目（当前 LTM 没有 category 字段时退化为空列表）
 */
public class ProfileSource implements ContextSource {

    private final PreferenceMemory pref;
    private final LongTermMemory ltm;

    public ProfileSource(PreferenceMemory pref, LongTermMemory ltm) {
        this.pref = pref; this.ltm = ltm;
    }

    @Override
    public String id() { return "profile"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.PROFILE; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        List<ContextItem> items = new ArrayList<>();
        if (pref != null) {
            // 取一次性快照，避免遍历期间被并发写入打断
            Map<String, String> data = new TreeMap<>(pref.getData());
            for (Map.Entry<String, String> e : data.entrySet()) {
                items.add(new ContextItem(e.getKey() + ": " + e.getValue(), 1.0, id()));
            }
        }
        if (ltm != null && !slot.getFilter().getCategories().isEmpty()) {
            int limit = slot.getFilter().getTopK();
            if (limit <= 0) limit = 10;
            int count = 0;
            for (MemoryItem item : ltm.getItems()) {
                if (count >= limit) break;
                items.add(new ContextItem(item.getContent(), item.getImportance(), id()));
                count++;
            }
        }
        return items;
    }
}
