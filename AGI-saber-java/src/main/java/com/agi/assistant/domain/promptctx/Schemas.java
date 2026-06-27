package com.agi.assistant.domain.promptctx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 4 类内置 Schema 的注册表（对应 Go promptctx.DefaultSchemas）。
 * 与全局/单槽位字符预算、裁剪优先级等常量一并放在此处。
 */
public final class Schemas {

    /** 全局字符预算（约等于 token 上限的 4 倍） */
    public static final int DEFAULT_GLOBAL_TOKEN_BUDGET = 2400;

    private Schemas() {}

    /** 普通对话：偏好 + 兜底召回；不需要 Planner / TaskMem / ToolState */
    public static final RuntimeContextSchema CHAT = new RuntimeContextSchema("chat", List.of(
            new Slot(SlotKind.CONSTRAINTS, false, filter(200)),
            new Slot(SlotKind.PROFILE, false, filter(300, 10, "identity", "preference")),
            new Slot(SlotKind.RECALL, false, filterRecall(400, 3, 0.4, "episodic", "fact", "general"))
    ));

    /** 单工具调用：弱化 Recall，强化 Tool State；不需要 Planner / TaskMem */
    public static final RuntimeContextSchema TOOL = new RuntimeContextSchema("tool", List.of(
            new Slot(SlotKind.CONSTRAINTS, false, filter(200)),
            new Slot(SlotKind.PROFILE, false, filter(250, 8, "identity", "preference")),
            new Slot(SlotKind.TOOL_STATE, true, filterTopK(350, 6)),
            new Slot(SlotKind.RECALL, false, filterRecall(250, 2, 0.5, "episodic", "fact", "general"))
    ));

    /** 多步推理：装配全部 5 类槽位 */
    public static final RuntimeContextSchema REACT = new RuntimeContextSchema("react", List.of(
            new Slot(SlotKind.CONSTRAINTS, true, filter(280)),
            new Slot(SlotKind.PLANNER, true, filter(300)),
            new Slot(SlotKind.TASK_MEMORY, false, filterTopKAge(350, 8, 24)),
            new Slot(SlotKind.TOOL_STATE, true, filterTopK(350, 8)),
            new Slot(SlotKind.PROFILE, false, filter(250, 6, "identity", "preference")),
            new Slot(SlotKind.RECALL, false, filterRecall(200, 2, 0.5,
                    "episodic", "fact", "general", "tool_failure"))
    ));

    /** 知识库检索：弱化 Planner/TaskMem，保留 Profile/Constraints/Recall */
    public static final RuntimeContextSchema RAG = new RuntimeContextSchema("rag", List.of(
            new Slot(SlotKind.CONSTRAINTS, false, filter(200)),
            new Slot(SlotKind.PROFILE, false, filter(300, 8, "identity", "preference")),
            new Slot(SlotKind.RECALL, false, filterRecall(400, 3, 0.4, "episodic", "fact", "general"))
    ));

    public static Map<String, RuntimeContextSchema> defaults() {
        Map<String, RuntimeContextSchema> map = new LinkedHashMap<>();
        map.put("chat", CHAT);
        map.put("tool", TOOL);
        map.put("react", REACT);
        map.put("rag", RAG);
        return map;
    }

    /** 全局预算超限时按此优先级裁剪（数字越小越优先保留） */
    public static int slotPriority(SlotKind kind) {
        return switch (kind) {
            case CONSTRAINTS -> 0;
            case PLANNER -> 1;
            case TASK_MEMORY -> 2;
            case TOOL_STATE -> 3;
            case PROFILE -> 4;
            case RECALL -> 5;
        };
    }

    public static String slotTitle(SlotKind kind) {
        return switch (kind) {
            case PROFILE -> "用户画像";
            case PLANNER -> "任务规划";
            case TASK_MEMORY -> "任务记忆";
            case TOOL_STATE -> "可用工具";
            case CONSTRAINTS -> "硬性约束";
            case RECALL -> "相关回忆";
        };
    }

    // ── filter 构造工具方法 ────────────────────────────────────────────────

    private static SlotFilter filter(int budget) {
        return new SlotFilter(budget);
    }

    private static SlotFilter filter(int budget, int topK, String... categories) {
        SlotFilter f = new SlotFilter(budget);
        f.setTopK(topK);
        f.setCategories(List.of(categories));
        return f;
    }

    private static SlotFilter filterTopK(int budget, int topK) {
        SlotFilter f = new SlotFilter(budget);
        f.setTopK(topK);
        return f;
    }

    private static SlotFilter filterTopKAge(int budget, int topK, int maxAgeHours) {
        SlotFilter f = new SlotFilter(budget);
        f.setTopK(topK);
        f.setMaxAgeHours(maxAgeHours);
        return f;
    }

    private static SlotFilter filterRecall(int budget, int topK, double minScore, String... categories) {
        SlotFilter f = new SlotFilter(budget);
        f.setTopK(topK);
        f.setMinScore(minScore);
        f.setCategories(List.of(categories));
        return f;
    }
}
