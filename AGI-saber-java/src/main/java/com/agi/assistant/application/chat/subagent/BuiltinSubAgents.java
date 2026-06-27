package com.agi.assistant.application.chat.subagent;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.document.Document;
import com.agi.assistant.domain.document.WriteRequest;
import com.agi.assistant.model.Tool;
import com.agi.assistant.service.agent.UnifiedAgentService;
import com.agi.assistant.service.document.DocumentLibraryService;
import com.agi.assistant.service.llm.LlmService;
import com.agi.assistant.service.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 四个内建子 Agent（对应 Go application/chat/subagents.go 的 researchAgent / writerAgent /
 * reviewAgent / docAgent）。
 *
 * <p>它们共享同一组依赖（LLM / RAG / 工具表 / 文档库），通过 {@link #registerInto} 一次性注册。</p>
 */
public final class BuiltinSubAgents {

    private BuiltinSubAgents() {}

    /** 在给定的 registry 上一次性注册 4 个内建子 Agent。 */
    public static void registerInto(SubAgentRegistry reg,
                                    AppConfig cfg,
                                    LlmService llm,
                                    RagService rag,
                                    UnifiedAgentService agent,
                                    DocumentLibraryService library) {
        reg.register(new ResearchAgent(cfg, llm, rag, agent));
        reg.register(new WriterAgent(cfg, llm));
        reg.register(new ReviewAgent(cfg, llm));
        reg.register(new DocAgent(library));
    }

    // ─────────────────────────── research_agent ───────────────────────────

    /** 多轮改写 + 知识库/搜索检索 + 证据整理。 */
    public static class ResearchAgent implements SubAgent {

        private static final ObjectMapper M = new ObjectMapper();
        private final AppConfig cfg;
        private final LlmService llm;
        private final RagService rag;
        private final UnifiedAgentService agent;

        public ResearchAgent(AppConfig cfg, LlmService llm, RagService rag, UnifiedAgentService agent) {
            this.cfg = cfg; this.llm = llm; this.rag = rag; this.agent = agent;
        }

        @Override public String name() { return "research_agent"; }
        @Override public String description() {
            return "Agentic RAG researcher: 多轮改写、知识库/搜索检索、证据整理。";
        }

        @Override
        public String run(SubAgentTask task, AtomicBoolean cancelled) {
            List<String> queries = planQueries(task);
            List<String> observations = new ArrayList<>();
            List<String> evidence = new ArrayList<>();
            for (String q : queries) {
                if (cancelled != null && cancelled.get()) break;
                if (rag != null && rag.isLoaded()) {
                    RagService.QueryResult qr = rag.query(q);
                    observations.add("Query: " + q + "\nRAG Answer: " + qr.answer);
                    if (qr.results != null) {
                        for (RagService.ScoredChunk sc : qr.results) {
                            String content = sc.chunk == null ? "" : sc.chunk.getContent();
                            evidence.add("- " + SubAgentSupport.firstRunes(content, 180));
                        }
                    }
                    continue;
                }
                Tool t = agent == null ? null : agent.getTools().get("search_web");
                if (t != null) {
                    try {
                        String out = t.getExecute().apply(Map.of("query", q));
                        observations.add("Query: " + q + "\nSearch Result: " + out);
                    } catch (Exception ignored) { /* swallow, try next */ }
                }
            }
            if (observations.isEmpty()) observations.add("未找到可用知识库或搜索结果。");
            String userMsg = String.format("研究目标：%s%n原始问题：%s%n%n观察结果：%n%s%n%n证据片段：%n%s",
                    task.goal, task.query, String.join("\n\n", observations),
                    String.join("\n", evidence));
            if (!cfg.isRealLLM()) return "## Research Findings\n\n" + userMsg;
            return llm.chat(
                    "你是 research_agent。请基于观察结果输出结构化研究摘要，包含 Findings、Evidence、Open Questions。不要编造未出现的信息。",
                    List.of(Map.of("role", "user", "content", userMsg)));
        }

        private List<String> planQueries(SubAgentTask task) {
            String base = task.goal == null ? "" : task.goal.strip();
            if (base.isEmpty()) base = task.query == null ? "" : task.query;
            if (!cfg.isRealLLM()) return List.of(base);
            String raw = llm.chat(
                    "你是查询规划器。请把研究目标改写成 2-3 条互补检索查询，严格输出 JSON：{\"queries\":[\"...\"]}",
                    List.of(Map.of("role", "user", "content", base)));
            if (raw == null) return List.of(base);
            raw = raw.strip().replace("```json", "").replace("```", "").strip();
            List<String> out = new ArrayList<>();
            out.add(base);
            try {
                Map<?, ?> parsed = M.readValue(raw, Map.class);
                Object qs = parsed.get("queries");
                if (qs instanceof List<?> l) {
                    for (Object o : l) {
                        if (o == null) continue;
                        String s = o.toString().strip();
                        if (!s.isEmpty() && out.size() < 3) out.add(s);
                    }
                }
            } catch (Exception ignored) { /* fall through */ }
            return SubAgentSupport.dedupStrings(out);
        }
    }

    // ─────────────────────────── writer_agent ───────────────────────────

    public static class WriterAgent implements SubAgent {
        private final AppConfig cfg;
        private final LlmService llm;

        public WriterAgent(AppConfig cfg, LlmService llm) {
            this.cfg = cfg; this.llm = llm;
        }

        @Override public String name() { return "writer_agent"; }
        @Override public String description() { return "将上游研究结果整理为 Markdown 报告。"; }

        @Override
        public String run(SubAgentTask task, AtomicBoolean cancelled) {
            String input = SubAgentSupport.upstreamText(task);
            if (!cfg.isRealLLM()) {
                return "# " + SubAgentSupport.safeTitle(task.goal, task.query) + "\n\n" + input;
            }
            return llm.chat(
                    "你是 writer_agent。请把输入整理为清晰 Markdown 报告，包含摘要、分析、建议和下一步。",
                    List.of(Map.of("role", "user", "content",
                            "写作目标：" + task.goal + "\n\n材料：\n" + input)));
        }
    }

    // ─────────────────────────── review_agent ───────────────────────────

    public static class ReviewAgent implements SubAgent {
        private final AppConfig cfg;
        private final LlmService llm;

        public ReviewAgent(AppConfig cfg, LlmService llm) {
            this.cfg = cfg; this.llm = llm;
        }

        @Override public String name() { return "review_agent"; }
        @Override public String description() { return "检查报告结构、事实一致性、证据覆盖和风险。"; }

        @Override
        public String run(SubAgentTask task, AtomicBoolean cancelled) {
            String input = SubAgentSupport.upstreamText(task);
            if (!cfg.isRealLLM()) return "Review: 内容已整理；建议人工确认关键事实。";
            return llm.chat(
                    "你是 review_agent。请审查输入，输出问题清单、可信度和需要补证据的点。",
                    List.of(Map.of("role", "user", "content", input)));
        }
    }

    // ─────────────────────────── doc_agent ───────────────────────────

    public static class DocAgent implements SubAgent {
        private static final ObjectMapper M = new ObjectMapper();
        private final DocumentLibraryService library;

        public DocAgent(DocumentLibraryService library) {
            this.library = library;
        }

        @Override public String name() { return "doc_agent"; }
        @Override public String description() {
            return "将上游结果保存到本地文档库，并同步写入 RAG。";
        }

        @Override
        public String run(SubAgentTask task, AtomicBoolean cancelled) throws Exception {
            if (library == null) throw new IllegalStateException("document library not configured");
            String content = SubAgentSupport.documentContent(task);
            if (content == null || content.isBlank()) content = task.query;
            String title = SubAgentSupport.documentTitle(content, task.goal, task.query);

            WriteRequest req = new WriteRequest(
                    title, "report", Document.SOURCE_AGENT, name(),
                    content, SubAgentSupport.firstRunes(content, 180), buildMetadata(task));
            DocumentLibraryService.Result res = library.writeDocument(req, true);

            // 返回 JSON（与 Go 的 jsonString(res) 等价的可读结构）
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("document", res.document);
            out.put("version", res.version);
            out.put("created", res.created);
            if (res.ingestChunks != null) {
                Map<String, Object> ingest = new LinkedHashMap<>();
                ingest.put("chunk_count", res.ingestChunks);
                ingest.put("doc_hash", res.ingestDocHash);
                out.put("ingest", ingest);
            }
            return M.writeValueAsString(out);
        }

        private Map<String, Object> buildMetadata(SubAgentTask task) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sub_agent", name());
            meta.put("task_id", task.id);
            String review = SubAgentSupport.upstreamByAgent(task, "review_agent");
            if (review != null) meta.put("review", SubAgentSupport.firstRunes(review, 1200));
            return meta;
        }
    }
}
