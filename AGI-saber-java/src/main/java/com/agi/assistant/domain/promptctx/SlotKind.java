package com.agi.assistant.domain.promptctx;

/**
 * 认知槽位的类别标识（对应 Go domain/promptctx.SlotKind）。
 *
 * <p>每轮推理前根据 Mode 选取一个 RuntimeContextSchema（认知槽位编排），
 * 装配器并发调用注册的 ContextSource 填充各槽位：</p>
 *
 * <ul>
 *   <li>Long-term Profile  — 用户稳定身份与偏好</li>
 *   <li>Planner State      — 当前任务规划/阶段</li>
 *   <li>Task Memory        — 当前任务步骤观察缓存</li>
 *   <li>Tool State         — 可用工具与近期调用结果</li>
 *   <li>Constraints        — 沙箱政策、硬性约束</li>
 *   <li>Recall Memory      — 受 SlotFilter 约束的语义召回（兜底）</li>
 * </ul>
 */
public enum SlotKind {
    PROFILE("profile"),
    PLANNER("planner"),
    TASK_MEMORY("task_memory"),
    TOOL_STATE("tool_state"),
    CONSTRAINTS("constraints"),
    RECALL("recall_memory");

    private final String value;

    SlotKind(String value) { this.value = value; }

    public String value() { return value; }
}
