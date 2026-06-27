package com.agi.assistant.domain.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务图（DAG）（对应 Go domain/graph/graph.go 的 TaskGraph）。
 *
 * <p>用 Kahn 算法做按层拓扑排序：同层节点之间相互无依赖，可被
 * {@code GraphRuntime} 并行调度；同 race_group 的节点会进入竞速分支，
 * 谁先成功取消其余的执行（first-success-wins）。</p>
 */
public class TaskGraph {

    /** id → node */
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /** node → 下游 node 列表（出边） */
    private final Map<String, List<String>> adjList = new LinkedHashMap<>();
    /** 入度表：剩余未满足的依赖数；-1 表示已处理 */
    private final Map<String, Integer> inDegree = new LinkedHashMap<>();
    /** 拓扑层级缓存 */
    private List<List<String>> levelsCache;

    public TaskGraph(List<Node> all) {
        if (all == null) return;
        for (Node n : all) {
            n.setStatus(NodeStatus.PENDING);
            nodes.put(n.getId(), n);
            adjList.put(n.getId(), new ArrayList<>());
            inDegree.put(n.getId(), 0);
        }
        for (Node n : all) {
            for (String dep : n.getDependsOn()) {
                if (!nodes.containsKey(dep)) continue;
                adjList.get(dep).add(n.getId());
                inDegree.merge(n.getId(), 1, Integer::sum);
            }
        }
    }

    public Map<String, Node> getNodes() { return nodes; }
    public Map<String, List<String>> getAdjList() { return adjList; }
    public Map<String, Integer> getInDegree() { return inDegree; }

    /**
     * 按"层"拓扑排序。同层节点彼此无依赖关系，可被并行调度。
     * @throws IllegalStateException 检测到环时抛出
     */
    public List<List<String>> topologicalLevels() {
        if (levelsCache != null) return levelsCache;
        Map<String, Integer> inDeg = new LinkedHashMap<>(inDegree);
        List<List<String>> levels = new ArrayList<>();
        int processed = 0;
        while (true) {
            List<String> ready = new ArrayList<>();
            for (Map.Entry<String, Integer> e : inDeg.entrySet()) {
                if (e.getValue() == 0) ready.add(e.getKey());
            }
            if (ready.isEmpty()) break;
            levels.add(ready);
            processed += ready.size();
            for (String id : ready) {
                inDeg.put(id, -1);
                for (String down : adjList.getOrDefault(id, Collections.emptyList())) {
                    inDeg.merge(down, -1, Integer::sum);
                }
            }
        }
        if (processed != nodes.size()) {
            throw new IllegalStateException(
                    "task graph has cycle: processed " + processed + "/" + nodes.size() + " nodes");
        }
        levelsCache = levels;
        return levels;
    }

    /** 校验图：悬空依赖 + 环检测。出错时抛 {@link IllegalStateException}。 */
    public void validate() {
        for (Node n : nodes.values()) {
            for (String dep : n.getDependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalStateException(
                            "node " + n.getId() + " depends on nonexistent node " + dep);
                }
            }
        }
        topologicalLevels();
    }

    /** 当前所有"入度=0 && status=pending"的可执行节点。 */
    public List<String> readyNodes() {
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0 && nodes.get(e.getKey()).getStatus() == NodeStatus.PENDING) {
                ready.add(e.getKey());
            }
        }
        return ready;
    }

    /** 标记一个节点完成，更新下游入度，返回新就绪的下游节点。 */
    public List<String> markDone(String id) {
        inDegree.put(id, -1);
        List<String> newlyReady = new ArrayList<>();
        for (String down : adjList.getOrDefault(id, Collections.emptyList())) {
            inDegree.merge(down, -1, Integer::sum);
            if (inDegree.get(down) == 0 && nodes.get(down).getStatus() == NodeStatus.PENDING) {
                newlyReady.add(down);
            }
        }
        return newlyReady;
    }

    /** 按 race_group 分组（仅含非空 race_group 的节点）。 */
    public Map<String, List<String>> raceGroups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Node n : nodes.values()) {
            if (n.getRaceGroup() != null && !n.getRaceGroup().isEmpty()) {
                groups.computeIfAbsent(n.getRaceGroup(), k -> new ArrayList<>()).add(n.getId());
            }
        }
        return groups;
    }

    public void setNodeStatus(String id, NodeStatus s) {
        Node n = nodes.get(id);
        if (n != null) n.setStatus(s);
    }

    public void setNodeResult(String id, String r) {
        Node n = nodes.get(id);
        if (n != null) n.setResult(r);
    }

    public void setNodeError(String id, String err) {
        Node n = nodes.get(id);
        if (n != null) n.setError(err);
    }

    public void setNodeRetryCount(String id, int count) {
        Node n = nodes.get(id);
        if (n != null) n.setRetryCount(count);
    }

    /** 所有成功节点的结果（带工具名/agent 名前缀），供 Generator LLM 使用。 */
    public List<String> successfulResults() {
        List<String> results = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.getStatus() == NodeStatus.DONE && n.getResult() != null && !n.getResult().isEmpty()) {
                results.add("[" + n.executorName() + "] " + n.getResult());
            }
        }
        return results;
    }

    /** 调试用文本摘要。 */
    public String summary() {
        StringBuilder b = new StringBuilder();
        List<List<String>> levels;
        try {
            levels = topologicalLevels();
        } catch (Exception e) {
            return "graph invalid: " + e.getMessage();
        }
        b.append("graph: ").append(nodes.size()).append(" nodes, ")
                .append(levels.size()).append(" levels\n");
        for (int i = 0; i < levels.size(); i++) {
            List<String> level = levels.get(i);
            List<String> parts = new ArrayList<>();
            for (String id : level) {
                Node n = nodes.get(id);
                parts.add(id + "(" + n.getToolName() + ")");
            }
            b.append("  L").append(i).append(": ").append(String.join(", ", parts)).append("\n");
        }
        return b.toString();
    }
}
