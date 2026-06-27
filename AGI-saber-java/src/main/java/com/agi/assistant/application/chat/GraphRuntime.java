package com.agi.assistant.application.chat;

import com.agi.assistant.application.chat.subagent.SubAgent;
import com.agi.assistant.application.chat.subagent.SubAgentRegistry;
import com.agi.assistant.application.chat.subagent.SubAgentTask;
import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.graph.Node;
import com.agi.assistant.domain.graph.NodeStatus;
import com.agi.assistant.domain.graph.NodeType;
import com.agi.assistant.domain.graph.TaskGraph;
import com.agi.assistant.model.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 任务图运行时（对应 Go application/chat/runtime_graph.go 的 GraphRuntime）。
 *
 * <p>调度策略：</p>
 * <ul>
 *   <li><b>按层调度</b>：对 {@link TaskGraph#topologicalLevels()} 的每一层，
 *       同层节点之间无依赖，可并行执行</li>
 *   <li><b>竞速分支</b>：同 {@code raceGroup} 的节点放进 {@code raceGroup(...)}，
 *       谁先成功胜出，其他节点收到取消信号并标记 {@link NodeStatus#SKIPPED}</li>
 *   <li><b>独立分支</b>：无 raceGroup 的节点彼此独立并行</li>
 *   <li><b>并发上限</b>：用 {@link Semaphore} 控制最大并行（{@code app.graph.max-parallel}）</li>
 *   <li><b>中断/取消</b>：每次执行前检查 {@link AtomicBoolean cancelled}，
 *       新一层不再启动；竞速分支会主动 interrupt 其余节点</li>
 *   <li><b>重试</b>：单节点按 harness.maxRetries / retryDelayMs 重试</li>
 * </ul>
 */
public class GraphRuntime {

    private static final Logger log = LoggerFactory.getLogger(GraphRuntime.class);

    public static class Config {
        public final int maxParallel;
        public final int raceTimeoutMs;
        public final boolean enableRacing;
        public Config(int maxParallel, int raceTimeoutMs, boolean enableRacing) {
            this.maxParallel = maxParallel <= 0 ? 2 : maxParallel;
            this.raceTimeoutMs = raceTimeoutMs <= 0 ? 30000 : raceTimeoutMs;
            this.enableRacing = enableRacing;
        }
        public static Config defaults() { return new Config(2, 30000, true); }
        public static Config from(AppConfig cfg) {
            AppConfig.GraphConfig g = cfg.getGraph();
            if (g == null) return defaults();
            return new Config(g.getMaxParallel(), g.getRaceTimeoutMs(), g.isEnableRacing());
        }
    }

    /** 单节点结果 */
    public static class NodeResult {
        public final NodeStatus status;
        public final String result;
        public final String error;
        public NodeResult(NodeStatus status, String result, String error) {
            this.status = status; this.result = result; this.error = error;
        }
    }

    /** 整图执行结果 */
    public static class GraphResult {
        public List<String> observations = new ArrayList<>();
        public Map<String, NodeResult> nodeResults = new LinkedHashMap<>();
        public boolean interrupted;
        public String interruptedAt = "";
        public String interruptedMsg = "";
    }

    private final TaskGraph graph;
    private final AppConfig appCfg;
    private final Config cfg;
    private final Map<String, Tool> tools;
    private final SubAgentRegistry subAgents;
    private final String taskQuery;
    private final Semaphore sem;
    private final Object mu = new Object();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final Consumer<StreamEvent> onEvent;
    private final AtomicBoolean cancelled;

    public GraphRuntime(TaskGraph graph, AppConfig appCfg, Config cfg,
                        Map<String, Tool> tools, AtomicBoolean cancelled,
                        Consumer<StreamEvent> onEvent) {
        this(graph, appCfg, cfg, tools, null, "", cancelled, onEvent);
    }

    public GraphRuntime(TaskGraph graph, AppConfig appCfg, Config cfg,
                        Map<String, Tool> tools, SubAgentRegistry subAgents,
                        String taskQuery, AtomicBoolean cancelled,
                        Consumer<StreamEvent> onEvent) {
        this.graph = graph;
        this.appCfg = appCfg;
        this.cfg = cfg == null ? Config.from(appCfg) : cfg;
        this.tools = tools;
        this.subAgents = subAgents;
        this.taskQuery = taskQuery == null ? "" : taskQuery;
        this.sem = new Semaphore(this.cfg.maxParallel);
        this.cancelled = cancelled;
        this.onEvent = onEvent == null ? e -> {} : onEvent;
    }

    /** 执行整张图，逐层并行调度 + 竞速。 */
    public GraphResult execute() {
        List<List<String>> levels;
        try {
            levels = graph.topologicalLevels();
        } catch (Exception e) {
            log.warn("GraphRuntime: 图校验失败 {}", e.getMessage());
            GraphResult r = new GraphResult();
            r.interrupted = true;
            r.interruptedMsg = "图校验失败: " + e.getMessage();
            return r;
        }

        // 转为字符串状态供前端订阅
        Map<String, Object> nodeView = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : graph.getNodes().entrySet()) {
            Node n = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", n.getId()); v.put("tool", n.getToolName());
            v.put("name", n.getName()); v.put("depends_on", n.getDependsOn());
            v.put("race_group", n.getRaceGroup()); v.put("status", n.getStatus().value());
            nodeView.put(e.getKey(), v);
        }
        onEvent.accept(StreamEvent.graphReady(levels, nodeView));

        // 调度 ExecutorService（同时给 race 用作 attempt pool）
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "graph-runtime");
            t.setDaemon(true);
            return t;
        });
        try {
            for (int li = 0; li < levels.size(); li++) {
                if (cancelled.get()) {
                    return interrupted("在第 " + li + " 层执行前被中断");
                }
                List<String> level = levels.get(li);
                List<RaceGroup> groups = groupByRace(level);
                CountDownLatch latch = new CountDownLatch(groups.size());
                for (RaceGroup g : groups) {
                    if (!g.raceGroup.isEmpty() && cfg.enableRacing && g.nodeIds.size() > 1) {
                        pool.execute(() -> { try { runRace(pool, g); } finally { latch.countDown(); } });
                    } else {
                        // 独立并行：组内每个节点都是一个独立子任务
                        CountDownLatch innerLatch = new CountDownLatch(g.nodeIds.size());
                        for (String id : g.nodeIds) {
                            pool.execute(() -> {
                                try { runSingle(id); } finally { innerLatch.countDown(); }
                            });
                        }
                        pool.execute(() -> {
                            try {
                                innerLatch.await();
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            } finally {
                                latch.countDown();
                            }
                        });
                    }
                }
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return interrupted("等待第 " + li + " 层时线程被中断");
                }
                if (cancelled.get()) {
                    return interrupted("在第 " + li + " 层执行后被中断");
                }
            }
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return buildResult();
    }

    // ─────────────── 竞速执行（First-success-wins） ───────────────

    private static class RaceGroup {
        final String raceGroup;
        final List<String> nodeIds;
        RaceGroup(String rg, List<String> ids) { this.raceGroup = rg; this.nodeIds = ids; }
    }

    private List<RaceGroup> groupByRace(List<String> level) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        List<String> noGroup = new ArrayList<>();
        for (String id : level) {
            Node n = graph.getNodes().get(id);
            String rg = n.getRaceGroup();
            if (rg != null && !rg.isEmpty()) {
                grouped.computeIfAbsent(rg, k -> new ArrayList<>()).add(id);
            } else {
                noGroup.add(id);
            }
        }
        List<RaceGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            result.add(new RaceGroup(e.getKey(), e.getValue()));
        }
        // 无 raceGroup 的节点合并为一个"普通组"，外层会在组内逐节点并行
        if (!noGroup.isEmpty()) result.add(new RaceGroup("", noGroup));
        result.sort(Comparator.comparing(g -> g.raceGroup));
        return result;
    }

    private void runRace(ExecutorService pool, RaceGroup g) {
        AtomicBoolean winnerFound = new AtomicBoolean(false);
        BlockingQueue<RaceAttempt> ch = new ArrayBlockingQueue<>(g.nodeIds.size());
        Map<String, Thread> threads = new HashMap<>();

        for (String id : g.nodeIds) {
            Thread t = new Thread(() -> {
                if (winnerFound.get() || cancelled.get()) {
                    ch.offer(new RaceAttempt(id, null, "竞速取消"));
                    return;
                }
                acquire();
                try {
                    if (winnerFound.get() || cancelled.get()) {
                        ch.offer(new RaceAttempt(id, null, "竞速取消"));
                        return;
                    }
                    String r = doExecuteNode(id, winnerFound);
                    if (r == null) {
                        ch.offer(new RaceAttempt(id, null, errorOf(id)));
                    } else {
                        ch.offer(new RaceAttempt(id, r, null));
                    }
                } catch (Throwable ex) {
                    ch.offer(new RaceAttempt(id, null, ex.getMessage()));
                } finally {
                    sem.release();
                }
            }, "race-" + g.raceGroup + "-" + id);
            t.setDaemon(true);
            threads.put(id, t);
            // 必须用 Thread.start()，不能走 pool.execute(t) ——
            // 后者会让 pool 的 worker 调 t.run()，t 自己的线程从未启动，
            // 后续对 t.interrupt() 无效，竞速失败者将无法被打断。
            t.start();
        }

        long deadline = System.currentTimeMillis() + cfg.raceTimeoutMs;
        String lastErr = null;
        int collected = 0;
        try {
            while (collected < g.nodeIds.size()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                RaceAttempt r = ch.poll(left, TimeUnit.MILLISECONDS);
                if (r == null) break;
                collected++;
                if (r.err == null && winnerFound.compareAndSet(false, true)) {
                    // 胜出 → 写结果 + 取消其余
                    Node node = graph.getNodes().get(r.id);
                    synchronized (mu) {
                        results.put(r.id, r.result);
                        graph.setNodeStatus(r.id, NodeStatus.DONE);
                        graph.setNodeResult(r.id, r.result);
                    }
                    onEvent.accept(StreamEvent.raceWon(g.raceGroup, r.id, node.getToolName()));
                    onEvent.accept(StreamEvent.observation(node.getToolName(), r.result));
                    // 中断其它仍在跑的线程（确保 sleep 重试能立即返回）
                    for (Map.Entry<String, Thread> e : threads.entrySet()) {
                        if (!e.getKey().equals(r.id)) e.getValue().interrupt();
                    }
                } else if (!winnerFound.get() && r.err != null) {
                    lastErr = r.err;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // 结算：未胜出节点 → 标 SKIPPED；都失败 → FAILED
        synchronized (mu) {
            if (winnerFound.get()) {
                for (String id : g.nodeIds) {
                    Node n = graph.getNodes().get(id);
                    if (n.getStatus() != NodeStatus.DONE) {
                        graph.setNodeStatus(id, NodeStatus.SKIPPED);
                    }
                }
            } else {
                for (String id : g.nodeIds) {
                    graph.setNodeStatus(id, NodeStatus.FAILED);
                    if (lastErr != null) {
                        graph.setNodeError(id, lastErr);
                        errors.put(id, lastErr);
                    }
                }
            }
        }
    }

    private static class RaceAttempt {
        final String id;
        final String result;
        final String err;
        RaceAttempt(String id, String result, String err) { this.id = id; this.result = result; this.err = err; }
    }

    // ─────────────── 单节点执行 ───────────────

    private void runSingle(String nodeId) {
        acquire();
        try {
            String r = doExecuteNode(nodeId, null);
            synchronized (mu) {
                if (r != null) results.put(nodeId, r);
            }
        } finally {
            sem.release();
        }
    }

    /**
     * 真正调用工具的核心：状态机、重试、SSE 事件、错误记录都在这里。
     * 当 winnerFlag 非 null 时（竞速分支），如果中途其他节点已胜出 → 提前返回 null。
     */
    private String doExecuteNode(String nodeId, AtomicBoolean winnerFlag) {
        Node node = graph.getNodes().get(nodeId);
        if (node == null) return null;
        String executor = node.executorName();
        onEvent.accept(StreamEvent.nodeStart(nodeId, executor));

        // Thought + Action 步骤
        onEvent.accept(StreamEvent.step(idAsInt(nodeId), node.getName()));
        onEvent.accept(StreamEvent.toolCall(executor, node.getParams()));

        graph.setNodeStatus(nodeId, NodeStatus.RUNNING);

        // 工具节点：先校验工具存在；子 Agent 节点：先校验注册
        if (node.getType() == NodeType.SUB_AGENT) {
            if (subAgents == null || !subAgents.has(node.getAgentName())) {
                String msg = "子 Agent " + node.getAgentName() + " 不存在";
                graph.setNodeStatus(nodeId, NodeStatus.FAILED);
                graph.setNodeError(nodeId, msg);
                onEvent.accept(StreamEvent.observation(executor, msg));
                synchronized (mu) { errors.put(nodeId, msg); }
                return null;
            }
        } else {
            if (tools.get(node.getToolName()) == null) {
                String msg = "工具 " + node.getToolName() + " 不在允许列表中";
                graph.setNodeStatus(nodeId, NodeStatus.FAILED);
                graph.setNodeError(nodeId, msg);
                onEvent.accept(StreamEvent.observation(executor, msg));
                synchronized (mu) { errors.put(nodeId, msg); }
                return null;
            }
        }

        AppConfig.HarnessConfig h = appCfg.getHarness();
        int maxRetries = h == null ? 3 : h.getMaxRetries();
        int retryDelay = h == null ? 200 : h.getRetryDelayMs();

        String result = null;
        String lastErr = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (cancelled.get()) {
                graph.setNodeStatus(nodeId, NodeStatus.CANCELLED);
                lastErr = "被用户中断";
                break;
            }
            if (winnerFlag != null && winnerFlag.get()) {
                // 竞速：他人胜出 → 不再重试，直接返回让外层标 SKIPPED
                return null;
            }
            try {
                result = invoke(node);
                lastErr = null;
                break;
            } catch (Exception e) {
                lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                graph.setNodeRetryCount(nodeId, attempt + 1);
                if (cancelled.get()) {
                    lastErr = "被用户中断";
                    graph.setNodeStatus(nodeId, NodeStatus.CANCELLED);
                    break;
                }
                if (attempt < maxRetries - 1) {
                    try { Thread.sleep(retryDelay); }
                    catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        // 竞速 / 取消引起的 interrupt → 立即返回
                        return null;
                    }
                }
            }
        }

        if (lastErr != null) {
            graph.setNodeStatus(nodeId, NodeStatus.FAILED);
            graph.setNodeError(nodeId, lastErr);
            synchronized (mu) { errors.put(nodeId, lastErr); }
            onEvent.accept(StreamEvent.observation(executor, "执行失败: " + lastErr));
            return null;
        }

        graph.setNodeStatus(nodeId, NodeStatus.DONE);
        graph.setNodeResult(nodeId, result == null ? "" : result);
        onEvent.accept(StreamEvent.nodeDone(nodeId, executor, NodeStatus.DONE.value()));
        if (winnerFlag == null) {
            // 普通分支立刻把 observation 推给前端；竞速分支由 runRace 在确认胜出后推送
            onEvent.accept(StreamEvent.observation(executor, result == null ? "" : result));
        }
        return result == null ? "" : result;
    }

    // ─────────────── 辅助方法 ───────────────

    /** 按节点类型分派：sub-agent 节点跑注册表里的 SubAgent；其余走工具调用。 */
    private String invoke(Node node) throws Exception {
        if (node.getType() == NodeType.SUB_AGENT) {
            SubAgent sa = subAgents.get(node.getAgentName());
            Map<String, String> upstream = upstreamResults(node);
            SubAgentTask task = new SubAgentTask(node.getId(), node.getGoal(), taskQuery, upstream);
            return sa.run(task, cancelled);
        }
        Tool t = tools.get(node.getToolName());
        Map<String, Object> params = new HashMap<>();
        if (node.getParams() != null) node.getParams().forEach(params::put);
        return t.getExecute().apply(params);
    }

    /** 取所有 depends_on 节点的 result，key = "<nodeId>:<executor-name>"，便于子 Agent 识别。 */
    private Map<String, String> upstreamResults(Node node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.getDependsOn() == null) return out;
        // 按 depId 排序，输出稳定（doc_agent 依赖该顺序定位 writer/review）
        for (String depId : new TreeSet<>(node.getDependsOn())) {
            Node dep = graph.getNodes().get(depId);
            if (dep == null || dep.getResult() == null || dep.getResult().isEmpty()) continue;
            String exec = dep.executorName();
            String key = exec.isEmpty() ? depId : depId + ":" + exec;
            out.put(key, dep.getResult());
        }
        return out;
    }

    private void acquire() {
        try { sem.acquire(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int idAsInt(String id) {
        if (id == null || id.isEmpty()) return 0;
        if (id.charAt(0) == 'n') {
            try { return Integer.parseInt(id.substring(1)); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private String errorOf(String nodeId) {
        synchronized (mu) {
            return errors.getOrDefault(nodeId, "未知错误");
        }
    }

    private GraphResult buildResult() {
        GraphResult r = new GraphResult();
        r.observations = graph.successfulResults();
        for (Map.Entry<String, Node> e : graph.getNodes().entrySet()) {
            Node n = e.getValue();
            r.nodeResults.put(e.getKey(), new NodeResult(n.getStatus(), n.getResult(), n.getError()));
        }
        return r;
    }

    private GraphResult interrupted(String msg) {
        // 把所有 pending/running 标 CANCELLED
        for (Node n : graph.getNodes().values()) {
            if (n.getStatus() == NodeStatus.PENDING || n.getStatus() == NodeStatus.RUNNING) {
                graph.setNodeStatus(n.getId(), NodeStatus.CANCELLED);
            }
        }
        GraphResult r = buildResult();
        r.interrupted = true;
        r.interruptedMsg = msg;
        return r;
    }

    /** 根据图节点状态生成中断摘要（对应 Go buildInterruptMessageFromGraph）。 */
    public static String buildInterruptMessage(TaskGraph g) {
        int done = 0;
        List<String> doneDesc = new ArrayList<>();
        List<String> pendingDesc = new ArrayList<>();
        for (Node n : g.getNodes().values()) {
            switch (n.getStatus()) {
                case DONE -> {
                    done++;
                    String r = n.getResult() == null ? "" : n.getResult();
                    if (r.length() > 30) r = r.substring(0, 30) + "…";
                    doneDesc.add(n.getId() + "(" + n.getToolName() + ")→" + r);
                }
                case PENDING, RUNNING, CANCELLED ->
                        pendingDesc.add(n.getId() + "(" + n.getToolName() + ")");
                default -> { /* failed/skipped 不计入未执行 */ }
            }
        }
        StringBuilder msg = new StringBuilder("已完成 ").append(done).append("/")
                .append(g.getNodes().size()).append(" 步");
        if (!doneDesc.isEmpty()) msg.append("：").append(String.join("；", doneDesc));
        if (!pendingDesc.isEmpty()) msg.append("；未执行：").append(String.join("、", pendingDesc));
        return msg.toString();
    }
}
