package com.agi.assistant.domain.graph;

/**
 * 任务图节点状态（对应 Go domain/graph/graph.go 的 NodeStatus）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>{@link #PENDING}    —— 等待依赖就绪 / 等待调度</li>
 *   <li>{@link #RUNNING}    —— 工具执行中</li>
 *   <li>{@link #DONE}       —— 成功完成</li>
 *   <li>{@link #FAILED}     —— 重试耗尽仍失败</li>
 *   <li>{@link #SKIPPED}    —— 同 race_group 内其他节点已胜出，被跳过</li>
 *   <li>{@link #CANCELLED}  —— 用户中断 / context 取消</li>
 * </ul>
 */
public enum NodeStatus {
    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    SKIPPED("skipped"),
    CANCELLED("cancelled");

    private final String value;
    NodeStatus(String value) { this.value = value; }
    public String value() { return value; }

    @Override public String toString() { return value; }
}
