package com.agi.assistant.domain.promptctx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 装配入口：根据 Mode 选 Schema，并发调各 source 填充槽位（对应 Go promptctx.ContextAssembler）。
 *
 * 最后做全局字符预算裁剪（高优先级槽位优先保留）。
 */
public class ContextAssembler {

    private final Map<String, RuntimeContextSchema> schemas;
    private final SourceRegistry registry;
    private final int globalLimit;

    public ContextAssembler(Map<String, RuntimeContextSchema> schemas, SourceRegistry registry) {
        this.schemas = (schemas == null || schemas.isEmpty()) ? Schemas.defaults() : schemas;
        this.registry = registry;
        this.globalLimit = Schemas.DEFAULT_GLOBAL_TOKEN_BUDGET;
    }

    public RuntimeContext assemble(Query q) {
        RuntimeContextSchema schema = schemas.getOrDefault(q.getMode(), schemas.get("chat"));

        List<Slot> slots = schema.getSlots();
        FilledSlot[] filled = new FilledSlot[slots.size()];

        // 并发填充每个槽位
        CompletableFuture<?>[] futures = new CompletableFuture[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            final int idx = i;
            final Slot slot = slots.get(i);
            futures[i] = CompletableFuture.runAsync(() -> filled[idx] = fillSlot(slot, q));
        }
        CompletableFuture.allOf(futures).join();

        RuntimeContext rc = new RuntimeContext(schema, new ArrayList<>(List.of(filled)));
        applyGlobalBudget(rc);
        return rc;
    }

    private FilledSlot fillSlot(Slot slot, Query q) {
        List<ContextSource> sources = registry.forKind(slot.getKind());
        if (sources.isEmpty()) {
            FilledSlot fs = new FilledSlot(slot.getKind());
            fs.setSkipped(slot.isRequired());
            fs.setReason("no source registered");
            return fs;
        }

        List<ContextItem> all = new ArrayList<>();
        for (ContextSource src : sources) {
            try {
                List<ContextItem> items = src.fetch(slot, q);
                if (items != null) all.addAll(items);
            } catch (Exception ignored) {
                break;
            }
        }

        if (all.isEmpty()) {
            FilledSlot fs = new FilledSlot(slot.getKind());
            fs.setSkipped(!slot.isRequired());
            fs.setReason("source returned empty");
            return fs;
        }
        if (slot.getFilter().getTokenBudget() > 0) {
            all = trimByBudget(all, slot.getFilter().getTokenBudget());
        }
        return new FilledSlot(slot.getKind(), all);
    }

    private void applyGlobalBudget(RuntimeContext rc) {
        int total = 0;
        for (FilledSlot fs : rc.getFilled()) {
            for (ContextItem item : fs.getItems()) {
                total += item.getText() == null ? 0 : item.getText().length();
            }
        }
        if (total <= globalLimit) return;

        // 按优先级从低到高排，逐步裁剪
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rc.getFilled().size(); i++) order.add(i);
        order.sort(Comparator.<Integer>comparingInt(idx ->
                Schemas.slotPriority(rc.getFilled().get(idx).getKind())).reversed());

        for (int idx : order) {
            if (total <= globalLimit) break;
            FilledSlot fs = rc.getFilled().get(idx);
            while (!fs.getItems().isEmpty() && total > globalLimit) {
                ContextItem last = fs.getItems().remove(fs.getItems().size() - 1);
                total -= last.getText() == null ? 0 : last.getText().length();
            }
            if (fs.getItems().isEmpty()) {
                fs.setSkipped(!rc.getSchema().getSlots().get(idx).isRequired());
                fs.setReason("global budget exceeded");
            }
        }
    }

    private static List<ContextItem> trimByBudget(List<ContextItem> items, int budget) {
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            int len = items.get(i).getText() == null ? 0 : items.get(i).getText().length();
            total += len;
            if (total > budget) {
                return new ArrayList<>(items.subList(0, i));
            }
        }
        return items;
    }
}
