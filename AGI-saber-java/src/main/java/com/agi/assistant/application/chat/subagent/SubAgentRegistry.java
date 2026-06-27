package com.agi.assistant.application.chat.subagent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 注册表（对应 Go application/chat.subAgentRegistry）。
 *
 * <p>线程安全：{@link #register(SubAgent)} 用 {@code computeIfAbsent} 风格写入，
 * {@link #snapshot()} 返回不可变副本以便 planner 安全遍历。</p>
 */
public class SubAgentRegistry {

    private final Map<String, SubAgent> agents = new ConcurrentHashMap<>();

    public void register(SubAgent a) {
        if (a == null || a.name() == null || a.name().isEmpty()) return;
        agents.put(a.name(), a);
    }

    public SubAgent get(String name) {
        if (name == null) return null;
        return agents.get(name);
    }

    public boolean has(String name) {
        return name != null && agents.containsKey(name);
    }

    public Map<String, SubAgent> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(agents));
    }
}
