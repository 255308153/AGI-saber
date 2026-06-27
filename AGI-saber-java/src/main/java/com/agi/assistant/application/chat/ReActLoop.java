package com.agi.assistant.application.chat;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.graph.Node;
import com.agi.assistant.domain.graph.NodeStatus;
import com.agi.assistant.domain.graph.TaskGraph;
import com.agi.assistant.dto.ChatResponse;
import com.agi.assistant.model.ReActStep;
import com.agi.assistant.model.TaskState;
import com.agi.assistant.model.TaskStep;
import com.agi.assistant.model.Tool;
import com.agi.assistant.service.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ReAct 多步循环（对应 Go application/chat/mode_react.go）。
 *
 * <p>从 UnifiedAgentService 抽出的核心循环：planner → 图并行执行（DAG + 竞速）→ 综合生成。
 * 同时支持同步与流式两种模式（流式模式下每步都向 onEvent 推送 StreamEvent）。</p>
 *
 * <p>调度模式：</p>
 * <ul>
 *   <li><b>DAG 模式</b>（默认，对应 {@code app.graph.enable-racing=true}）：
 *       Planner 输出带 {@code depends_on}/{@code race_group} 的节点，
 *       由 {@link GraphRuntime} 按层并行调度，同 race_group 节点竞速 first-success-wins。</li>
 *   <li><b>线性兜底</b>：Planner 没有 / 解析为空 → 走 LLM 直答；
 *       图校验失败 → 把所有节点的 depends_on 清空，退化为全并行。</li>
 * </ul>
 */
public class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final AppConfig cfg;
    private final LlmService llm;
    private final Planner planner;
    private final ChatGenerator generator;
    private final SnapshotManager snapshots;
    private final com.agi.assistant.application.chat.subagent.SubAgentRegistry subAgents;

    public ReActLoop(AppConfig cfg, LlmService llm,
                     Planner planner, ChatGenerator generator,
                     SnapshotManager snapshots) {
        this(cfg, llm, planner, generator, snapshots, null);
    }

    public ReActLoop(AppConfig cfg, LlmService llm,
                     Planner planner, ChatGenerator generator,
                     SnapshotManager snapshots,
                     com.agi.assistant.application.chat.subagent.SubAgentRegistry subAgents) {
        this.cfg = cfg;
        this.llm = llm;
        this.planner = planner;
        this.generator = generator;
        this.snapshots = snapshots;
        this.subAgents = subAgents;
    }

    /** 同步入口（兼容旧路径），等价于 runStream(... , e -> {})。 */
    public void run(ChatResponse resp, String query, Map<String, Tool> ts,
                    String memPrefix, List<Map<String, String>> histMsgs,
                    AtomicBoolean cancelled) {
        runStream(resp, query, ts, memPrefix, histMsgs, cancelled, e -> {});
    }

    /**
     * 流式入口。每完成一个语义事件就回调一次 onEvent：
     * step / tool_call / observation / graph_ready / node_start / node_done / race_won。
     */
    public void runStream(ChatResponse resp, String query, Map<String, Tool> ts,
                          String memPrefix, List<Map<String, String>> histMsgs,
                          AtomicBoolean cancelled, Consumer<StreamEvent> onEvent) {
        List<ReActStep> reactSteps = new ArrayList<>();

        // ── Step 1: Planner 输出带依赖的图节点 ──────────────────────────────
        List<Node> planNodes = planner.planGraph(query, ts, memPrefix);

        if (planNodes == null || planNodes.isEmpty()) {
            String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                    "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
            String answer = llm.chat(sp, histMsgs);
            reactSteps.add(new ReActStep(ReActStep.THOUGHT, "分析后无需调用工具，直接回答"));
            reactSteps.add(new ReActStep(ReActStep.FINAL_ANSWER, answer));
            resp.setAnswer(answer);
            resp.setSteps(reactSteps);
            return;
        }

        // ── Step 2: 构建 TaskGraph（校验失败则降级为全并行） ────────────────
        TaskGraph tg;
        try {
            tg = new TaskGraph(planNodes);
            tg.validate();
        } catch (Exception e) {
            log.warn("TaskGraph 校验失败 ({})，降级为全并行执行", e.getMessage());
            for (Node n : planNodes) n.setDependsOn(new ArrayList<>());
            tg = new TaskGraph(planNodes);
        }

        // 兼容旧 Snapshot/SSE：把图节点平展成 TaskStep
        TaskState currentTask = new TaskState();
        currentTask.setTaskId("task-" + System.nanoTime());
        currentTask.setQuery(query);
        currentTask.setStatus("running");
        currentTask.setPhase("executing");
        currentTask.setSteps(graphToTaskSteps(tg));
        snapshots.clear();
        snapshots.save(currentTask);

        // ── Step 3: GraphRuntime 并行/竞速执行 ──────────────────────────────
        GraphRuntime rt = new GraphRuntime(tg, cfg, GraphRuntime.Config.from(cfg),
                ts, subAgents, query, cancelled, onEvent);
        GraphRuntime.GraphResult gr = rt.execute();
        // 同步图状态回 TaskState（保留旧的快照视图）
        syncGraphIntoTaskSteps(tg, currentTask);
        snapshots.save(currentTask);
        reactSteps.addAll(graphResultToReActSteps(tg));

        // ── Step 4: 中断检查 ─────────────────────────────────────────────
        if (cancelled.get() || gr.interrupted) {
            currentTask.setPhase("interrupted");
            currentTask.setStatus("interrupted");
            String msg = GraphRuntime.buildInterruptMessage(tg);
            resp.setAnswer("[已中断] " + msg);
            resp.setSteps(reactSteps);
            resp.setTask(currentTask);
            resp.setInterrupted(true);
            return;
        }

        // ── Step 5: Generator LLM 综合所有观察 ───────────────────────────
        currentTask.setPhase("generating");
        String answer = generator.generate(query, gr.observations, memPrefix, histMsgs);
        reactSteps.add(new ReActStep(ReActStep.FINAL_ANSWER, answer));
        currentTask.setResult(answer);
        currentTask.setStatus("completed");
        currentTask.setPhase("done");
        resp.setAnswer(answer);
        resp.setSteps(reactSteps);
        resp.setTask(currentTask);
    }

    // ─────────────────────────── Graph ↔ TaskStep 适配 ───────────────────────────

    /** 把图节点按拓扑层平展成 TaskStep，便于现有 Snapshot / SSE 复用。 */
    private static List<TaskStep> graphToTaskSteps(TaskGraph tg) {
        List<TaskStep> steps = new ArrayList<>();
        int counter = 0;
        for (List<String> level : tg.topologicalLevels()) {
            for (String id : level) {
                counter++;
                Node n = tg.getNodes().get(id);
                steps.add(new TaskStep(counter, n.getName(), n.getToolName(),
                        n.getParams() == null ? new HashMap<>() : new HashMap<>(n.getParams())));
            }
        }
        return steps;
    }

    /** 把图节点状态/结果回填到 TaskStep（按 toolName + name 配对，简单可靠）。 */
    private static void syncGraphIntoTaskSteps(TaskGraph tg, TaskState task) {
        if (task.getSteps() == null) return;
        // 按拓扑顺序遍历图节点，与 TaskStep 一一对应
        List<String> ordered = new ArrayList<>();
        for (List<String> level : tg.topologicalLevels()) ordered.addAll(level);
        for (int i = 0; i < ordered.size() && i < task.getSteps().size(); i++) {
            Node n = tg.getNodes().get(ordered.get(i));
            TaskStep s = task.getSteps().get(i);
            s.setStatus(mapStatus(n.getStatus()));
            s.setResult(n.getResult());
            s.setError(n.getError());
            s.setRetryCount(n.getRetryCount());
        }
    }

    private static String mapStatus(NodeStatus st) {
        return switch (st) {
            case DONE -> TaskStep.DONE;
            case FAILED -> TaskStep.FAILED;
            case RUNNING -> TaskStep.RUNNING;
            case CANCELLED -> TaskStep.INTERRUPTED;
            case SKIPPED, PENDING -> TaskStep.PENDING;
        };
    }

    /** 把执行完的图节点转回旧 ReActStep 序列（Thought/Action/Observation）。 */
    private static List<ReActStep> graphResultToReActSteps(TaskGraph tg) {
        List<ReActStep> steps = new ArrayList<>();
        for (List<String> level : tg.topologicalLevels()) {
            for (String id : level) {
                Node n = tg.getNodes().get(id);
                steps.add(new ReActStep(ReActStep.THOUGHT, n.getName()));
                steps.add(new ReActStep(ReActStep.ACTION, "调用 " + n.getToolName(),
                        n.getToolName(), n.getParams()));
                switch (n.getStatus()) {
                    case DONE -> steps.add(new ReActStep(ReActStep.OBSERVATION, n.getResult()));
                    case FAILED -> steps.add(new ReActStep(ReActStep.OBSERVATION,
                            "执行失败: " + n.getError()));
                    case SKIPPED -> steps.add(new ReActStep(ReActStep.OBSERVATION,
                            "[竞速跳过] 其他节点已胜出"));
                    case CANCELLED -> steps.add(new ReActStep(ReActStep.OBSERVATION, "[已中断]"));
                    default -> { /* PENDING/RUNNING：理论上不应到此 */ }
                }
            }
        }
        return steps;
    }
}
