package com.agi.assistant.domain.promptctx.source;

import com.agi.assistant.domain.promptctx.ContextItem;
import com.agi.assistant.domain.promptctx.ContextSource;
import com.agi.assistant.domain.promptctx.Query;
import com.agi.assistant.domain.promptctx.Slot;
import com.agi.assistant.domain.promptctx.SlotKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 装填 Task Memory 槽位（对应 Go promptctx.TaskMemSource）。
 */
public class TaskMemSource implements ContextSource {

    private final TaskMemBuffer buffer;

    public TaskMemSource(TaskMemBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public String id() { return "task_memory"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.TASK_MEMORY; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        if (buffer == null) return List.of();
        List<StepObservation> obs = buffer.snapshot();
        if (obs.isEmpty()) return List.of();

        int topK = slot.getFilter().getTopK();
        if (topK > 0 && obs.size() > topK) {
            obs = obs.subList(obs.size() - topK, obs.size());
        }

        List<ContextItem> items = new ArrayList<>();
        for (StepObservation o : obs) {
            String text = "步骤" + o.getStepId() + " [" + o.getToolName() + "]";
            if (o.isSuccess()) {
                String r = o.getResult() == null ? "" : o.getResult();
                if (r.length() > 200) r = r.substring(0, 200) + "…";
                text += "→" + r;
            } else {
                text += " 失败: " + (o.getError() == null ? "" : o.getError());
            }
            items.add(new ContextItem(text, 0, id(), Map.of("tool", o.getToolName())));
        }
        return items;
    }
}
