package com.agi.assistant.application.chat.subagent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 Agent 接口（对应 Go application/chat.SubAgent）。
 *
 * <p>实现类应该是自包含的：内部持有自己的 LLM / RAG / 文档库句柄，
 * 通过 {@link SubAgentTask#upstream} 拿到上游节点的产出。{@code cancelled}
 * 用于支持中断 —— 长时间任务在循环里轮询它，看见 true 就早返回。</p>
 */
public interface SubAgent {

    /** 注册名（也是 Planner JSON 里写的 agent 字段）。 */
    String name();

    /** 给规划器看的一句话能力描述。 */
    String description();

    /** 执行子 Agent；抛异常表示失败，由 GraphRuntime 决定是否重试。 */
    String run(SubAgentTask task, AtomicBoolean cancelled) throws Exception;
}
