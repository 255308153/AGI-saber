package com.agi.assistant.application.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 流式事件（对应 Go application/chat/stream_event.go）。
 *
 * <p>由 {@link ReActLoop} / {@link ChatApplicationService#processStream} 在执行过程中
 * 推送给 controller 的 SseEmitter。每个事件都有 {@code type} 字段，前端按 type 分发。</p>
 *
 * <p>事件类型：</p>
 * <ul>
 *   <li>{@code start} —— 任务开始，data = {message}</li>
 *   <li>{@code mode}  —— 选定模式（chat/tool/rag/react），data = {mode}</li>
 *   <li>{@code step}  —— 进入 ReAct 步骤 N，data = {idx, name}</li>
 *   <li>{@code tool_call} —— 调用工具前，data = {tool, params}</li>
 *   <li>{@code observation} —— 工具返回，data = {tool, result}</li>
 *   <li>{@code rag_result} —— RAG 检索结果，data = {chunks}</li>
 *   <li>{@code token} —— LLM 流式 token（如果 LLM 支持），data = {chunk}</li>
 *   <li>{@code done}  —— 任务结束，data = ChatResponse</li>
 *   <li>{@code error} —— 异常退出，data = {message}</li>
 * </ul>
 */
public class StreamEvent {

    public final String type;
    public final Object data;

    public StreamEvent(String type, Object data) {
        this.type = type;
        this.data = data;
    }

    public String type() { return type; }
    public Object data() { return data; }

    public static StreamEvent start(String message) {
        return new StreamEvent("start", Map.of("message", message == null ? "" : message));
    }

    public static StreamEvent mode(String mode) {
        return new StreamEvent("mode", Map.of("mode", mode));
    }

    public static StreamEvent step(int idx, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", idx);
        m.put("name", name);
        return new StreamEvent("step", m);
    }

    public static StreamEvent toolCall(String tool, Map<String, ?> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("params", params);
        return new StreamEvent("tool_call", m);
    }

    public static StreamEvent observation(String tool, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("result", result);
        return new StreamEvent("observation", m);
    }

    public static StreamEvent ragResult(List<?> chunks) {
        return new StreamEvent("rag_result", Map.of("chunks", chunks == null ? List.of() : chunks));
    }

    public static StreamEvent token(String chunk) {
        return new StreamEvent("token", Map.of("chunk", chunk == null ? "" : chunk));
    }

    public static StreamEvent done(Object response) {
        return new StreamEvent("done", response);
    }

    public static StreamEvent error(String message) {
        return new StreamEvent("error", Map.of("message", message == null ? "" : message));
    }

    // ─────────────── DAG / 竞速调度（对应 Go GraphRuntime 推送的事件） ───────────────

    /** 图就绪：data = {levels: [[id,...], ...], nodes: {...}} */
    public static StreamEvent graphReady(List<List<String>> levels, Map<String, ?> nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("levels", levels == null ? List.of() : levels);
        m.put("nodes", nodes == null ? Map.of() : nodes);
        return new StreamEvent("graph_ready", m);
    }

    /** 节点开始执行：data = {id, tool} */
    public static StreamEvent nodeStart(String id, String tool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("tool", tool == null ? "" : tool);
        return new StreamEvent("node_start", m);
    }

    /** 节点完成：data = {id, tool, status} */
    public static StreamEvent nodeDone(String id, String tool, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("tool", tool == null ? "" : tool);
        m.put("status", status == null ? "" : status);
        return new StreamEvent("node_done", m);
    }

    /** 竞速胜出：data = {race_group, winner, tool} */
    public static StreamEvent raceWon(String raceGroup, String winner, String tool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("race_group", raceGroup == null ? "" : raceGroup);
        m.put("winner", winner == null ? "" : winner);
        m.put("tool", tool == null ? "" : tool);
        return new StreamEvent("race_won", m);
    }
}
