package com.agi.assistant.application.chat;

import com.agi.assistant.application.chat.subagent.SubAgent;
import com.agi.assistant.application.chat.subagent.SubAgentRegistry;
import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.graph.Node;
import com.agi.assistant.domain.graph.NodeType;
import com.agi.assistant.model.Tool;
import com.agi.assistant.model.ToolParam;
import com.agi.assistant.service.llm.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务规划器（对应 Go application/chat/planner.go + plan_graph.go）。
 *
 * <p>提供两条路径：</p>
 * <ul>
 *   <li>{@link #plan(String, Map, String)} —— 旧线性 PlanItem 列表，保留兼容</li>
 *   <li>{@link #planGraph(String, Map, String)} —— 输出 DAG 节点（带依赖、竞速组、可选 sub-agent）</li>
 * </ul>
 *
 * <p>{@link SubAgentRegistry} 在构造时可注入：</p>
 * <ul>
 *   <li>非空 → planner 会把已注册 sub-agent 的描述喂给 LLM，LLM 可以在 JSON 里输出
 *       {@code type:"sub_agent", agent:"writer_agent", goal:"…"} 的节点</li>
 *   <li>null   → planner 仅产 tool 节点，向后兼容旧用法</li>
 * </ul>
 */
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final AppConfig cfg;
    private final LlmService llm;
    private final SubAgentRegistry subAgents;

    public Planner(AppConfig cfg, LlmService llm) {
        this(cfg, llm, null);
    }

    public Planner(AppConfig cfg, LlmService llm, SubAgentRegistry subAgents) {
        this.cfg = cfg;
        this.llm = llm;
        this.subAgents = subAgents;
    }

    /** 一个规划项：要调用的工具 + 参数 + 原因 */
    public static class PlanItem {
        public String tool;
        public Map<String, String> params;
        public String reason;
        public PlanItem(String tool, Map<String, String> params, String reason) {
            this.tool = tool; this.params = params; this.reason = reason;
        }
    }

    public List<PlanItem> plan(String query, Map<String, Tool> ts, String memPrefix) {
        if (!cfg.isRealLLM()) return rulePlan(query, ts);

        StringBuilder toolLines = new StringBuilder();
        for (Map.Entry<String, Tool> e : ts.entrySet()) {
            String name = e.getKey();
            Tool t = e.getValue();
            StringBuilder pDescs = new StringBuilder();
            if (t.getParameters() != null) {
                for (ToolParam p : t.getParameters()) {
                    if (pDescs.length() > 0) pDescs.append(", ");
                    pDescs.append(p.getName()).append("(").append(p.getType()).append(")");
                    if (p.isRequired()) pDescs.append("（必填）");
                }
            }
            toolLines.append("- ").append(name).append(": ").append(t.getDescription())
                    .append(" [参数: ").append(pDescs.length() > 0 ? pDescs : "无参数").append("]\n");
        }

        String planPrompt = String.format("""
                你是一个任务规划器。根据用户问题，从可用工具中选出真正需要调用的工具（不要为了用工具而用工具，按需选择）。

                用户问题：%s

                可用工具：
                %s
                请以 JSON 数组格式输出执行计划，格式如下：
                [{"tool":"工具名","params":{"参数名":"参数值"},"reason":"一句话说明为什么调用这个工具"}]

                如果无需工具直接回答，输出 []。只输出 JSON，不要其他内容。""", query, toolLines);

        String plannerBase = "你是一个精准的任务规划器，只在必要时才调用工具，不做无意义的调用。";
        if (memPrefix != null && !memPrefix.isEmpty()) {
            plannerBase = memPrefix + "\n\n" + plannerBase + "\n注意：用户偏好可能影响工具参数选择（如城市、时区等），请在参数中体现。";
        }

        String raw = llm.chat(plannerBase, List.of(Map.of("role", "user", "content", planPrompt)));
        if (raw == null) return rulePlan(query, ts);
        raw = raw.trim().replace("```json", "").replace("```", "").trim();

        try {
            List<Map<String, Object>> items = M.readValue(raw,
                    M.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<PlanItem> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String tool = (String) item.get("tool");
                if (ts.containsKey(tool)) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = item.get("params") != null ?
                            (Map<String, String>) item.get("params") : new HashMap<>();
                    String reason = item.get("reason") != null ? item.get("reason").toString() : "调用" + tool;
                    result.add(new PlanItem(tool, params, reason));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Planner LLM 解析失败 ({}), 降级到规则规划", e.getMessage());
            return rulePlan(query, ts);
        }
    }

    private List<PlanItem> rulePlan(String query, Map<String, Tool> ts) {
        String q = query.toLowerCase();
        List<PlanItem> items = new ArrayList<>();
        if (ts.containsKey("get_time") && (q.contains("时间") || q.contains("几点") || q.contains("现在"))) {
            Map<String, String> params = new HashMap<>();
            if (q.contains("东京")) params.put("timezone", "Asia/Tokyo");
            items.add(new PlanItem("get_time", params, "查询当前时间"));
        }
        if (ts.containsKey("get_weather") && q.contains("天气")) {
            Map<String, String> params = new HashMap<>();
            for (String c : List.of("东京", "北京", "上海", "广州", "深圳", "纽约", "伦敦")) {
                if (q.contains(c)) { params.put("city", c); break; }
            }
            items.add(new PlanItem("get_weather", params,
                    "查询" + params.getOrDefault("city", "当地") + "天气"));
        }
        if (ts.containsKey("search_web") && (q.contains("搜索") || q.contains("查询") || q.contains("介绍")
                || q.contains("是什么") || q.contains("怎么") || q.contains("如何"))) {
            items.add(new PlanItem("search_web", Map.of("query", query), "搜索相关信息"));
        }
        if (ts.containsKey("rag_search")) {
            items.add(new PlanItem("rag_search", Map.of("query", query), "检索个人知识库"));
        }
        return items;
    }

    // ───────────────────────── DAG / 竞速规划（对应 Go llmPlanGraph） ─────────────────────────

    /**
     * 输出带依赖与竞速组的图节点（对应 Go application/chat/plan_graph.go 的 llmPlanGraph）。
     *
     * <p>返回的 {@link Node} 列表会被外层送进 {@code TaskGraph} → {@code GraphRuntime}：</p>
     * <ul>
     *   <li>{@code dependsOn} 描述节点间的数据依赖（B 需要 A 的输出 → B.dependsOn=[A]）</li>
     *   <li>{@code raceGroup} 把功能等价的节点（如多个搜索源）划入同一竞速组，
     *       谁先成功谁胜出，其他节点会被取消并标记 SKIPPED</li>
     * </ul>
     *
     * <p>LLM 不可用 / 解析失败时降级到 {@link #rulePlanNodes(String, Map)}，
     * 不保证 race_group / 依赖，只保证基本可执行。</p>
     */
    public List<Node> planGraph(String query, Map<String, Tool> ts, String memPrefix) {
        String qLower = query == null ? "" : query.toLowerCase();
        // 用户问题命中 sub-agent 关键词时直接走规则规划，避免 LLM 把 research/report 拆成离散工具调用
        if (needsSubAgentPlan(qLower)) return rulePlanNodes(query, ts);
        if (!cfg.isRealLLM()) return rulePlanNodes(query, ts);

        StringBuilder toolLines = new StringBuilder();
        for (Map.Entry<String, Tool> e : ts.entrySet()) {
            String name = e.getKey();
            Tool t = e.getValue();
            StringBuilder pDescs = new StringBuilder();
            if (t.getParameters() != null) {
                for (ToolParam p : t.getParameters()) {
                    if (pDescs.length() > 0) pDescs.append(", ");
                    pDescs.append(p.getName()).append("(").append(p.getType()).append(")");
                    if (p.isRequired()) pDescs.append("（必填）");
                }
            }
            toolLines.append("- ").append(name).append(": ").append(t.getDescription())
                    .append(" [参数: ").append(pDescs.length() > 0 ? pDescs : "无").append("]\n");
        }
        StringBuilder agentLines = new StringBuilder();
        if (subAgents != null) {
            for (Map.Entry<String, SubAgent> e : subAgents.snapshot().entrySet()) {
                agentLines.append("- ").append(e.getKey()).append(": ")
                        .append(e.getValue().description()).append("\n");
            }
        }

        String planPrompt = String.format("""
                你是一个任务规划器。根据用户问题，从可用工具和可用子 Agent 中选出需要调用的节点，并标注它们之间的依赖关系。

                规则：
                - 给每个节点分配一个唯一 id（如 n1, n2, n3...）
                - type 只能是 "tool" 或 "sub_agent"
                - 工具节点填写 tool 和 params；子 Agent 节点填写 agent 和 goal
                - 如果节点 B 需要节点 A 的输出，则 B 的 depends_on 包含 A 的 id
                - 如果两个工具功能类似（如多个搜索源），设相同的 race_group，系统会并行执行谁先返回用谁
                - 无依赖关系的节点不要互相等待，depends_on 设为 []
                - 需要研究、总结、报告、写文档时，优先使用 research_agent / writer_agent / review_agent / doc_agent 组合
                - doc_agent 负责把上游内容保存到本地文档库并写入 RAG

                用户问题：%s
                可用工具：
                %s

                可用子 Agent：
                %s

                请以 JSON 数组格式输出执行计划：
                [{"id":"n1","type":"sub_agent","agent":"research_agent","goal":"研究目标","params":{},"reason":"一句话说明为什么调用","depends_on":[],"race_group":""}]
                如果无需工具直接回答，输出 []。只输出 JSON，不要其他内容.""",
                query, toolLines, agentLines.length() == 0 ? "（无）" : agentLines);

        String plannerBase = "你是一个精准的任务规划器，只在必要时才调用工具，不做无意义的调用。能识别工具间的依赖关系和可竞速的同类工具。";
        if (memPrefix != null && !memPrefix.isEmpty()) {
            plannerBase = memPrefix + "\n\n" + plannerBase + "\n注意：用户偏好可能影响工具参数选择（如城市、时区等），请在参数中体现。";
        }

        String raw = llm.chat(plannerBase, List.of(Map.of("role", "user", "content", planPrompt)));
        if (raw == null) return rulePlanNodes(query, ts);
        raw = raw.trim();
        // 兼容旧/新 function-calling 风格
        int begin = raw.indexOf("<|FunctionCallBegin|>");
        if (begin >= 0) {
            raw = raw.substring(begin + "<|FunctionCallBegin|>".length());
            int end = raw.indexOf("<|FunctionCallEnd|>");
            if (end >= 0) raw = raw.substring(0, end);
        }
        raw = raw.replace("```json", "").replace("```", "").trim();

        // 1) 优先解析新格式：[{"id","type","tool"|"agent","goal","params","reason","depends_on","race_group"}]
        try {
            List<Map<String, Object>> items = M.readValue(raw,
                    M.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<Node> result = new ArrayList<>();
            int counter = 0;
            for (Map<String, Object> item : items) {
                counter++;
                String type = item.get("type") == null ? "" : item.get("type").toString();
                String tool = item.get("tool") == null ? "" : item.get("tool").toString();
                String agent = item.get("agent") == null ? "" : item.get("agent").toString();
                // type 缺失时按 agent 字段推断
                NodeType nodeType;
                if (type.isEmpty()) {
                    nodeType = agent.isEmpty() ? NodeType.TOOL : NodeType.SUB_AGENT;
                } else {
                    nodeType = NodeType.fromValue(type);
                }
                // 校验"执行体"存在
                if (nodeType == NodeType.SUB_AGENT) {
                    if (subAgents == null || !subAgents.has(agent)) continue;
                } else {
                    if (tool.isEmpty() || !ts.containsKey(tool)) continue;
                }
                String id = item.get("id") != null && !item.get("id").toString().isEmpty()
                        ? item.get("id").toString() : "n" + counter;
                Map<String, String> params = new HashMap<>();
                Object rawParams = item.get("params");
                if (rawParams instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> en : m.entrySet()) {
                        if (en.getKey() != null) params.put(en.getKey().toString(),
                                en.getValue() == null ? "" : en.getValue().toString());
                    }
                }
                String reason = item.get("reason") != null ? item.get("reason").toString() : "";
                String goal = item.get("goal") != null ? item.get("goal").toString() : reason;
                String name = reason.isEmpty() ? goal : reason;
                List<String> deps = new ArrayList<>();
                Object rawDeps = item.get("depends_on");
                if (rawDeps instanceof List<?> ld) {
                    for (Object o : ld) if (o != null) deps.add(o.toString());
                }
                String rg = item.get("race_group") != null ? item.get("race_group").toString() : "";

                if (nodeType == NodeType.SUB_AGENT) {
                    result.add(Node.subAgent(id, name, agent, goal, deps, rg));
                } else {
                    result.add(new Node(id, NodeType.TOOL, name, tool, params, deps, rg));
                }
            }
            if (!result.isEmpty()) return result;
        } catch (Exception ignored) { /* fall through */ }

        // 2) 兼容旧格式 [{"tool","params","reason"}]
        try {
            List<Map<String, Object>> items = M.readValue(raw,
                    M.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<Node> result = new ArrayList<>();
            int counter = 0;
            for (Map<String, Object> item : items) {
                String tool = (String) item.get("tool");
                if (tool == null || !ts.containsKey(tool)) continue;
                counter++;
                Map<String, String> params = new HashMap<>();
                Object rawParams = item.get("params");
                if (rawParams instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> en : m.entrySet()) {
                        if (en.getKey() != null) params.put(en.getKey().toString(),
                                en.getValue() == null ? "" : en.getValue().toString());
                    }
                }
                String reason = item.get("reason") != null ? item.get("reason").toString() : "调用 " + tool;
                result.add(new Node("n" + counter, NodeType.TOOL, reason, tool, params,
                        new ArrayList<>(), ""));
            }
            if (!result.isEmpty()) return result;
        } catch (Exception ignored) { /* fall through */ }

        // 3) 再降级：function-calling [{"name","parameters"}]
        try {
            List<Map<String, Object>> items = M.readValue(raw,
                    M.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<Node> result = new ArrayList<>();
            int counter = 0;
            for (Map<String, Object> item : items) {
                String tool = (String) item.get("name");
                if (tool == null || !ts.containsKey(tool)) continue;
                counter++;
                Map<String, String> params = new HashMap<>();
                Object rawParams = item.get("parameters");
                if (rawParams instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> en : m.entrySet()) {
                        if (en.getKey() != null) params.put(en.getKey().toString(),
                                en.getValue() == null ? "" : en.getValue().toString());
                    }
                }
                result.add(new Node("n" + counter, NodeType.TOOL, "LLM 规划调用", tool,
                        params, new ArrayList<>(), ""));
            }
            if (!result.isEmpty()) return result;
        } catch (Exception ignored) { /* fall through */ }

        log.warn("Planner LLM 图规划解析失败，降级为规则规划。原始输出: {}", raw);
        return rulePlanNodes(query, ts);
    }

    /**
     * 关键词规则规划（无真实 LLM 时用），把同类搜索源放进相同 race_group。
     * 与 Go rulePlanNodes 行为一致。
     */
    private List<Node> rulePlanNodes(String query, Map<String, Tool> ts) {
        String q = query.toLowerCase();
        List<Node> nodes = new ArrayList<>();
        int[] counter = {0};
        java.util.function.Supplier<String> nextID = () -> "n" + (++counter[0]);

        // 子 Agent 规划链：research → writer → review，可选 doc。
        // 即使 subAgents 注册表为空，也保留 sub_agent 节点以便测试断言。
        if (needsSubAgentPlan(q)) {
            String researchID = nextID.get();
            String writerID = nextID.get();
            String reviewID = nextID.get();
            nodes.add(Node.subAgent(researchID, "子 Agent 研究与证据收集",
                    "research_agent", "围绕用户任务进行多轮检索和证据整理",
                    new ArrayList<>(), ""));
            nodes.add(Node.subAgent(writerID, "子 Agent 生成报告",
                    "writer_agent", "基于研究结果生成 Markdown 报告",
                    new ArrayList<>(List.of(researchID)), ""));
            nodes.add(Node.subAgent(reviewID, "子 Agent 审查报告",
                    "review_agent", "检查报告质量、风险和证据缺口",
                    new ArrayList<>(List.of(writerID)), ""));
            if (wantsDocumentWrite(q)) {
                nodes.add(Node.subAgent(nextID.get(), "子 Agent 保存文档",
                        "doc_agent", "保存报告到本地文档库并写入 RAG",
                        new ArrayList<>(List.of(writerID, reviewID)), ""));
            }
            return nodes;
        }

        if (ts.containsKey("get_time")
                && (q.contains("时间") || q.contains("几点") || q.contains("现在"))) {
            Map<String, String> params = new HashMap<>();
            if (q.contains("东京")) params.put("timezone", "Asia/Tokyo");
            nodes.add(new Node(nextID.get(), NodeType.TOOL, "查询当前时间", "get_time",
                    params, new ArrayList<>(), ""));
        }
        if (ts.containsKey("get_weather") && q.contains("天气")) {
            Map<String, String> params = new HashMap<>();
            for (String c : List.of("东京", "北京", "上海", "广州", "深圳", "纽约", "伦敦")) {
                if (q.contains(c)) { params.put("city", c); break; }
            }
            String displayCity = params.getOrDefault("city", "当地");
            nodes.add(new Node(nextID.get(), NodeType.TOOL, "查询" + displayCity + "天气", "get_weather",
                    params, new ArrayList<>(), ""));
        }
        if (ts.containsKey("search_web")
                && (q.contains("搜索") || q.contains("查询") || q.contains("介绍")
                    || q.contains("是什么") || q.contains("怎么") || q.contains("如何"))) {
            nodes.add(new Node(nextID.get(), NodeType.TOOL, "搜索相关信息", "search_web",
                    new HashMap<>(Map.of("query", query)), new ArrayList<>(), "search"));
        }
        if (ts.containsKey("exec_command")
                && (q.contains("执行") || q.contains("运行") || q.contains("命令")
                    || q.contains("终端") || q.contains("lscpu") || q.contains("cpu")
                    || q.contains("磁盘") || q.contains("内存") || q.contains("系统信息"))) {
            nodes.add(new Node(nextID.get(), NodeType.TOOL, "执行终端命令", "exec_command",
                    new HashMap<>(Map.of("command", query)), new ArrayList<>(), ""));
        }
        if (ts.containsKey("rag_search")) {
            // 与 search_web 同 race_group → 优先取先返回的检索结果
            nodes.add(new Node(nextID.get(), NodeType.TOOL, "检索个人知识库", "rag_search",
                    new HashMap<>(Map.of("query", query)), new ArrayList<>(), "search"));
        }
        // 其余 MCP / 自定义工具（无规则匹配的）按"独立并行 + query 兜底"加入
        java.util.Set<String> builtins = java.util.Set.of(
                "get_time", "get_weather", "search_web", "rag_search", "exec_command");
        for (Map.Entry<String, Tool> e : ts.entrySet()) {
            if (builtins.contains(e.getKey())) continue;
            Map<String, String> params = new HashMap<>();
            if (e.getValue().getParameters() != null) {
                for (ToolParam p : e.getValue().getParameters()) {
                    if (p.isRequired()) { params.put(p.getName(), query); break; }
                }
            }
            nodes.add(new Node(nextID.get(), NodeType.TOOL, "调用工具 " + e.getKey(), e.getKey(),
                    params, new ArrayList<>(), ""));
        }
        return nodes;
    }

    /** 命中"研究/调研/总结/报告/文档/方案/分析"任一关键词，就走 sub-agent 链路。 */
    static boolean needsSubAgentPlan(String q) {
        if (q == null) return false;
        return q.contains("研究") || q.contains("调研") || q.contains("总结") ||
               q.contains("报告") || q.contains("文档") || q.contains("方案") ||
               q.contains("分析");
    }

    /** 命中"保存/落库/写入/文档库/知识库/生成报告/报告"，则在链路尾部追加 doc_agent。 */
    static boolean wantsDocumentWrite(String q) {
        if (q == null) return false;
        return q.contains("保存") || q.contains("落库") || q.contains("写入") ||
               q.contains("文档库") || q.contains("知识库") ||
               q.contains("生成报告") || q.contains("报告");
    }
}
