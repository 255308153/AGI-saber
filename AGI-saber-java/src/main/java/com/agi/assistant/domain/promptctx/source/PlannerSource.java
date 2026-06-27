package com.agi.assistant.domain.promptctx.source;

import com.agi.assistant.domain.promptctx.ContextItem;
import com.agi.assistant.domain.promptctx.ContextSource;
import com.agi.assistant.domain.promptctx.Query;
import com.agi.assistant.domain.promptctx.Slot;
import com.agi.assistant.domain.promptctx.SlotKind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 装填 Planner State 槽位（对应 Go promptctx.PlannerSource）。
 */
public class PlannerSource implements ContextSource {

    /** 由 application 层实现，返回当前任务的 Planner 状态；没有正在执行的任务时返回 null。 */
    private final Supplier<PlannerSnapshot> provider;

    public PlannerSource(Supplier<PlannerSnapshot> provider) {
        this.provider = provider;
    }

    @Override
    public String id() { return "planner"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.PLANNER; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        if (provider == null) return List.of();
        PlannerSnapshot snap = provider.get();
        if (snap == null) return List.of();

        List<ContextItem> items = new ArrayList<>();
        items.add(new ContextItem(
                String.format("任务 %s 状态=%s 阶段=%s",
                        snap.getTaskId(), snap.getStatus(), snap.getPhase()), 0, id()));
        if (snap.getTotalSteps() > 0) {
            items.add(new ContextItem(
                    String.format("进度：第 %d/%d 步",
                            snap.getCurrentStep() + 1, snap.getTotalSteps()), 0, id()));
        }
        if (snap.getNextStepName() != null && !snap.getNextStepName().isEmpty()) {
            items.add(new ContextItem(
                    String.format("下一步：%s（工具=%s）",
                            snap.getNextStepName(), snap.getNextStepTool()), 0, id()));
        }
        if ("interrupted".equals(snap.getStatus()) && snap.getInterruptedAt() > 0) {
            items.add(new ContextItem(
                    String.format("上次在第 %d 步被中断，可从此处恢复",
                            snap.getInterruptedAt() + 1), 0, id()));
        }
        return items;
    }
}
