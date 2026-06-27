package com.agi.assistant.application.chat.subagent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子 Agent 的执行任务（对应 Go application/chat.SubAgentTask）。
 *
 * <p>由 GraphRuntime 在调度子 Agent 节点时构造：</p>
 * <ul>
 *   <li>{@link #id}       — 当前节点 id（如 "n2"），用于在 Upstream 中识别本节点</li>
 *   <li>{@link #goal}     — Planner / 规则给出的"该 sub-agent 要做的事"</li>
 *   <li>{@link #query}    — 用户原始问题（贯穿整条链路）</li>
 *   <li>{@link #upstream} — 已完成的上游节点结果，key = "<node-id>:<agent/tool name>"</li>
 * </ul>
 */
public class SubAgentTask {

    public final String id;
    public final String goal;
    public final String query;
    public final Map<String, String> upstream;

    public SubAgentTask(String id, String goal, String query, Map<String, String> upstream) {
        this.id = id == null ? "" : id;
        this.goal = goal == null ? "" : goal;
        this.query = query == null ? "" : query;
        this.upstream = upstream == null ? new LinkedHashMap<>() : upstream;
    }
}
