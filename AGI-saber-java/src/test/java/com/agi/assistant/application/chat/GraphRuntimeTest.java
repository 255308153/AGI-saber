package com.agi.assistant.application.chat;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.graph.Node;
import com.agi.assistant.domain.graph.NodeStatus;
import com.agi.assistant.domain.graph.NodeType;
import com.agi.assistant.domain.graph.TaskGraph;
import com.agi.assistant.model.Tool;
import com.agi.assistant.model.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphRuntime 行为冒烟测试：
 *  1) 拓扑层并行 + 依赖正确传递
 *  2) 同 race_group 节点 first-success-wins，落败者标 SKIPPED
 *  3) 中断信号能停止后续层
 */
class GraphRuntimeTest {

    private static AppConfig minimalCfg() {
        AppConfig cfg = new AppConfig();
        cfg.getHarness().setMaxRetries(1);
        cfg.getHarness().setRetryDelayMs(1);
        cfg.getGraph().setMaxParallel(4);
        cfg.getGraph().setRaceTimeoutMs(2000);
        cfg.getGraph().setEnableRacing(true);
        return cfg;
    }

    private static Tool sleepTool(String name, long ms, String result) {
        return new Tool(name, "sleep " + ms,
                List.of(new ToolParam("q", "string", "q", false)),
                params -> {
                    try { Thread.sleep(ms); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted");
                    }
                    return result;
                });
    }

    @Test
    void topologicalLevelsRespectDependencies() {
        AtomicInteger order = new AtomicInteger();
        Map<String, Tool> tools = new HashMap<>();
        Map<String, Integer> seenAt = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c")) {
            tools.put(id, new Tool(id, "", List.of(),
                    params -> {
                        seenAt.put(id, order.incrementAndGet());
                        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                        return id + "-ok";
                    }));
        }

        Node a = new Node("n1", NodeType.TOOL, "A", "a", new HashMap<>(), List.of(), "");
        Node b = new Node("n2", NodeType.TOOL, "B", "b", new HashMap<>(), List.of(), "");
        Node c = new Node("n3", NodeType.TOOL, "C", "c", new HashMap<>(), List.of("n1", "n2"), "");
        TaskGraph tg = new TaskGraph(List.of(a, b, c));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        GraphRuntime rt = new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, cancelled, e -> {});
        GraphRuntime.GraphResult r = rt.execute();

        assertFalse(r.interrupted);
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n1").getStatus());
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n2").getStatus());
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n3").getStatus());
        assertTrue(seenAt.get("c") > seenAt.get("a"), "c must run after a");
        assertTrue(seenAt.get("c") > seenAt.get("b"), "c must run after b");
    }

    @Test
    void raceGroupKeepsFirstSuccess() {
        Map<String, Tool> tools = new HashMap<>();
        tools.put("fast", sleepTool("fast", 30, "fast-result"));
        tools.put("slow", sleepTool("slow", 300, "slow-result"));

        Node fast = new Node("n1", NodeType.TOOL, "fast", "fast", new HashMap<>(), List.of(), "search");
        Node slow = new Node("n2", NodeType.TOOL, "slow", "slow", new HashMap<>(), List.of(), "search");
        TaskGraph tg = new TaskGraph(List.of(fast, slow));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        GraphRuntime rt = new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, cancelled, e -> {});
        GraphRuntime.GraphResult r = rt.execute();

        assertFalse(r.interrupted);
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n1").getStatus());
        // 慢节点要么没机会跑完被中断，要么直接 SKIPPED；总之不应是 DONE
        assertNotEquals(NodeStatus.DONE, tg.getNodes().get("n2").getStatus());
        assertEquals("fast-result", tg.getNodes().get("n1").getResult());
    }

    @Test
    void cancelStopsScheduling() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Map<String, Tool> tools = new HashMap<>();
        tools.put("a", new Tool("a", "", List.of(),
                params -> { cancelled.set(true); return "a"; }));
        tools.put("b", sleepTool("b", 200, "b"));

        Node a = new Node("n1", NodeType.TOOL, "A", "a", new HashMap<>(), List.of(), "");
        Node b = new Node("n2", NodeType.TOOL, "B", "b", new HashMap<>(), List.of("n1"), "");
        TaskGraph tg = new TaskGraph(List.of(a, b));

        GraphRuntime rt = new GraphRuntime(tg, minimalCfg(), GraphRuntime.Config.from(minimalCfg()),
                tools, cancelled, e -> {});
        GraphRuntime.GraphResult r = rt.execute();

        assertTrue(r.interrupted);
        assertEquals(NodeStatus.DONE, tg.getNodes().get("n1").getStatus());
        // b 在层进入前被取消 → CANCELLED
        assertEquals(NodeStatus.CANCELLED, tg.getNodes().get("n2").getStatus());
    }
}
