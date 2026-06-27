package com.agi.assistant.domain.promptctx.source;

import com.agi.assistant.domain.promptctx.ContextItem;
import com.agi.assistant.domain.promptctx.ContextSource;
import com.agi.assistant.domain.promptctx.Query;
import com.agi.assistant.domain.promptctx.Slot;
import com.agi.assistant.domain.promptctx.SlotKind;
import com.agi.assistant.model.MemoryItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装填 Recall 槽位（兜底语义召回）（对应 Go promptctx.RecallSource）。
 *
 * 任何实现 Recaller 接口的对象（LongTerm / GraphMemory）都可以挂接。
 */
public class RecallSource implements ContextSource {

    /** 抽象 LongTerm / GraphMemory 共有的过滤召回能力 */
    public interface Recaller {
        List<MemoryItem> recall(String query, int topK, List<Double> queryEmbedding);
    }

    private final Recaller recaller;

    public RecallSource(Recaller recaller) { this.recaller = recaller; }

    @Override
    public String id() { return "recall"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.RECALL; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        if (recaller == null) return List.of();
        int topK = slot.getFilter().getTopK();
        if (topK <= 0) topK = 3;
        List<MemoryItem> hits = recaller.recall(q.getText(), topK, q.getEmbedding());
        if (hits == null || hits.isEmpty()) return List.of();

        List<ContextItem> items = new ArrayList<>();
        for (MemoryItem h : hits) {
            if (slot.getFilter().getMinScore() > 0 && h.getScore() < slot.getFilter().getMinScore()) {
                continue;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            String text = String.format("%s（重要性=%.2f, 综合分=%.2f）",
                    h.getContent(), h.getImportance(), h.getScore());
            items.add(new ContextItem(text, h.getScore(), id(), meta));
        }
        return items;
    }
}
