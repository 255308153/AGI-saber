package com.agi.assistant.service.agent;

import com.agi.assistant.application.chat.ChatGenerator;
import com.agi.assistant.application.chat.ChatHistoryAdapter;
import com.agi.assistant.application.chat.ChatRouter;
import com.agi.assistant.application.chat.MemoryWriter;
import com.agi.assistant.application.chat.Planner;
import com.agi.assistant.application.chat.ReActLoop;
import com.agi.assistant.application.chat.SnapshotManager;
import com.agi.assistant.application.chat.StreamEvent;
import com.agi.assistant.application.chat.ToolModeHandler;
import com.agi.assistant.config.AppConfig;
import com.agi.assistant.dto.ChatRequest;
import com.agi.assistant.dto.ChatResponse;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.infrastructure.tool.TavilyClient;
import com.agi.assistant.model.MemoryItem;
import com.agi.assistant.model.Snapshot;
import com.agi.assistant.model.Tool;
import com.agi.assistant.model.ToolParam;
import com.agi.assistant.service.graph.KGStore;
import com.agi.assistant.service.llm.LlmService;
import com.agi.assistant.service.memory.GraphMemory;
import com.agi.assistant.service.memory.LongTermMemory;
import com.agi.assistant.service.memory.PreferenceMemory;
import com.agi.assistant.service.memory.ShortTermMemory;
import com.agi.assistant.service.rag.RagService;
import com.agi.assistant.domain.sandbox.ExecResult;
import com.agi.assistant.domain.sandbox.Sandbox;
import com.agi.assistant.infrastructure.sandbox.SandboxFactory;
import com.agi.assistant.service.tools.ExecCommandTool;
import com.agi.assistant.service.tools.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UnifiedAgentService —— 已经从 854 行的上帝类拆分为多个 application/chat 协作类。
 *
 * <p>本类现在只承担：</p>
 * <ul>
 *   <li>启动期把所有依赖装配起来（PostConstruct）</li>
 *   <li>对外暴露 {@code process(...)} / {@code processStream(...)} 入口</li>
 *   <li>暴露 accessor（被 controller 用作只读窥视点）</li>
 * </ul>
 *
 * <p>核心循环、规划、生成、快照、记忆写入、模式路由 —— 全部移到了
 * {@code com.agi.assistant.application.chat.*}：</p>
 * <ul>
 *   <li>{@link ChatRouter} —— 模式路由</li>
 *   <li>{@link Planner} —— 任务规划</li>
 *   <li>{@link ReActLoop} —— ReAct 多步循环（同步 + 流式）</li>
 *   <li>{@link ToolModeHandler} —— 单步工具模式</li>
 *   <li>{@link ChatGenerator} —— 综合生成</li>
 *   <li>{@link SnapshotManager} —— 快照</li>
 *   <li>{@link MemoryWriter} —— 回复后的记忆 LLM 分类与写入</li>
 *   <li>{@link ChatHistoryAdapter} —— STM → LLM 消息列表</li>
 * </ul>
 */
@Service
public class UnifiedAgentService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentService.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final AppConfig cfg;
    private final LlmService llm;
    private final RagService rag;
    private final ToolService toolService;
    private final ShortTermMemory stm;
    private final LongTermMemory ltm;
    private final PreferenceMemory pref;
    private final InfrastructureService infra;

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 知识图谱（RAG 三路融合 + 记忆图共享） */
    private KGStore kg;
    /** 图增强长期记忆 */
    private GraphMemory graphMem;
    /** 沙箱执行 */
    private Sandbox sandbox;

    // ----- application/chat 协作者（在 init 中实例化） -----
    private MemoryWriter memoryWriter;
    private SnapshotManager snapshotManager;
    private Planner planner;
    private ChatGenerator generator;
    private ReActLoop reactLoop;
    private ToolModeHandler toolHandler;
    private com.agi.assistant.application.chat.subagent.SubAgentRegistry subAgents;
    private final com.agi.assistant.service.document.DocumentLibraryService library;

    public UnifiedAgentService(AppConfig cfg, LlmService llm, RagService rag, ToolService toolService,
                               ShortTermMemory stm, LongTermMemory ltm, PreferenceMemory pref,
                               InfrastructureService infra,
                               com.agi.assistant.service.document.DocumentLibraryService library) {
        this.cfg = cfg;
        this.llm = llm;
        this.rag = rag;
        this.toolService = toolService;
        this.stm = stm;
        this.ltm = ltm;
        this.pref = pref;
        this.infra = infra;
        this.library = library;
    }

    @PostConstruct
    public void init() {
        stm.setMaxTurns(cfg.getMemory().getShortTermMaxTurns());
        ltm.setConsolidationConfig(cfg.getMemory().getConsolidation());

        tools.putAll(toolService.getDefaultTools());

        // RAG 回调（带记忆前缀）
        rag.setGenerateFn((systemPrompt, userMsg) -> {
            String memPrefix = buildMemorySystemPrefix();
            String fullSystem = systemPrompt;
            if (!memPrefix.isEmpty()) {
                fullSystem = memPrefix + "\n\n" + systemPrompt + "\n结合用户偏好和记忆，用用户熟悉的方式回答。";
            }
            return llm.chat(fullSystem, List.of(Map.of("role", "user", "content", userMsg)));
        });
        rag.setEmbedFn(text -> llm.embed(text));

        infra.initRAGInfra(cfg.getRag().getRagMilvusDim());

        if (cfg.getRag().getRewrite().isEnabled() && cfg.getRag().getRewrite().getNumQueries() > 1) {
            rag.setRewriter(new com.agi.assistant.domain.rag.LLMRewriter(
                    (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))),
                    cfg.getRag().getRewrite().getNumQueries()));
        }
        if (cfg.getRag().getRerank().isEnabled()) {
            rag.setReranker(new com.agi.assistant.domain.rag.LLMReranker(
                    (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))),
                    cfg.getRag().getRerank().getPreviewLen()));
        }

        // rag_search 工具
        tools.put("rag_search", new Tool("rag_search", "从私人黑洞（个人知识库）中检索相关文档内容",
                List.of(new ToolParam("query", "string", "检索关键词或问题", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "相关内容";
                    if (!rag.isLoaded()) throw new RuntimeException("知识库为空，请先在「私人黑洞」上传文档");
                    return rag.query(q).answer;
                }));

        // search_web 工具（Tavily + LLM fallback）
        tools.put("search_web", new Tool("search_web", "搜索互联网获取最新信息",
                List.of(new ToolParam("query", "string", "搜索关键词", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "";
                    if (q.isEmpty()) throw new RuntimeException("搜索关键词不能为空");
                    if (cfg.getSearch().getApiKey() != null && !cfg.getSearch().getApiKey().isEmpty()) {
                        try {
                            return TavilyClient.search(q, cfg.getSearch().getApiKey(), cfg.getSearch().getApiUrl());
                        } catch (Exception ignored) {}
                    }
                    return llm.chat(
                            "你是一个知识丰富的搜索引擎助手。请基于你的知识，对用户的搜索问题给出准确、详细的回答。直接给出答案，不要说「我不知道」或「我无法搜索」。",
                            List.of(Map.of("role", "user", "content", "搜索：" + q)));
                }));

        restoreFromDB();
        restoreRAGFromDB();
        initKnowledgeGraph();

        // ===== application/chat 协作者装配 =====
        memoryWriter = new MemoryWriter(cfg, llm, pref, ltm, graphMem, infra);
        snapshotManager = new SnapshotManager(infra);
        subAgents = new com.agi.assistant.application.chat.subagent.SubAgentRegistry();
        com.agi.assistant.application.chat.subagent.BuiltinSubAgents.registerInto(
                subAgents, cfg, llm, rag, this, library);
        planner = new Planner(cfg, llm, subAgents);
        generator = new ChatGenerator(cfg, llm);
        reactLoop = new ReActLoop(cfg, llm, planner, generator, snapshotManager, subAgents);
        toolHandler = new ToolModeHandler(llm, toolService, pref);

        initSandbox();

        log.info("UnifiedAgent 初始化完成: tools={}, STM={}, LTM={}, Prefs={}, KG={}, Sandbox={}",
                tools.size(), stm.size(), ltm.size(), pref.getData().size(),
                kg != null && kg.available() ? "ready" : "off",
                sandbox != null ? sandbox.backend() : "off");
    }

    @PreDestroy
    public void shutdown() {
        if (kg != null) kg.close();
    }

    private void initKnowledgeGraph() {
        kg = new KGStore(cfg, (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))));
        rag.setKGStore(kg);

        graphMem = new GraphMemory(ltm, kg, cfg.getMemory().getConsolidation().getSimilarityThreshold());
        graphMem.syncPrevId();

        if (kg.available()) {
            log.info("知识图谱已就绪 (Neo4j)，RAG 升级为三路混合检索，记忆系统接入图层");
        } else {
            log.info("Neo4j 不可用，RAG 保持双路检索，记忆系统退化为纯向量模式");
        }
    }

    private void initSandbox() {
        if (!cfg.getSandbox().isEnabled()) {
            log.info("沙箱未启用 (sandbox.enabled=false)，跳过 exec_command 工具");
            return;
        }
        sandbox = SandboxFactory.build(cfg.getSandbox().getBackend(), cfg.getSandbox(), cfg.getSecurity());
        sandbox.setAuditFn(this::auditSandboxResult);
        tools.put("exec_command", ExecCommandTool.create(sandbox));
        log.info("沙箱已就绪，后端={}，exec_command 已注册", sandbox.backend());
    }

    private void auditSandboxResult(ExecResult r) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("command", r.getCommand());
            if (r.getValidation() != null) {
                event.put("level", r.getValidation().getLevel().value());
                event.put("reason", r.getValidation().getReason());
                event.put("violations", r.getValidation().getViolations());
            }
            event.put("exit_code", r.getExitCode());
            event.put("duration_ms", r.getDurationMs());
            event.put("backend", r.getBackend());
            event.put("killed", r.isKilled());
            event.put("truncated", r.isTruncated());
            infra.publishEvent("sandbox.exec", mapper.writeValueAsString(event));
        } catch (Exception ignored) {}
    }

    // ===== Public API =====

    public ChatResponse process(String query) {
        return processWithOptions(query, new ChatRequest());
    }

    public ChatResponse processWithOptions(String query, ChatRequest req) {
        return processInternal(query, req, e -> {});
    }

    /** 流式入口（对应 Go application/chat.ProcessStream）。 */
    public ChatResponse processStream(String query, ChatRequest req, Consumer<StreamEvent> onEvent) {
        return processInternal(query, req, onEvent == null ? e -> {} : onEvent);
    }

    private ChatResponse processInternal(String query, ChatRequest req, Consumer<StreamEvent> onEvent) {
        cancelled.set(false);
        ChatResponse resp = new ChatResponse();
        resp.setQuery(query);
        resp.setMode("chat");

        onEvent.accept(StreamEvent.start(query));

        stm.add("user", query);
        infra.saveChatHistory("user", query);

        // 异步偏好抽取（保留旧行为；MemoryWriter 是回复后才跑的，两者互不冲突）
        runAsyncPreferenceExtraction(query);

        // 同步规则提取
        String[] extracted = pref.extractAndSave(query);
        if (extracted != null) {
            resp.setExtractedInfo("已记住：" + extracted[0] + " = " + extracted[1]);
        }

        String memPrefix = buildMemorySystemPrefixWithCtx(query);
        List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query);

        if (cancelled.get()) {
            resp.setInterrupted(true);
            resp.setAnswer("[已中断] 请求在开始前被取消");
            return resp;
        }

        // 模式决策
        String mode = ChatRouter.decideMode(query, req.isExplicit(), req.isUseRag(),
                req.getSelectedTools(), rag.isLoaded());
        Map<String, Tool> toolset = tools;
        if (req.isExplicit() && req.getSelectedTools() != null && !req.getSelectedTools().isEmpty()) {
            Map<String, Tool> filtered = filterTools(req.getSelectedTools());
            if (!filtered.isEmpty()) {
                toolset = filtered;
            } else {
                mode = "chat";
            }
        }

        resp.setMode(mode);
        onEvent.accept(StreamEvent.mode(mode));

        switch (mode) {
            case "react" -> reactLoop.runStream(resp, query, toolset, memPrefix, histMsgs, cancelled, onEvent);
            case "tool" -> toolHandler.run(resp, query, toolset, memPrefix, histMsgs);
            case "rag" -> {
                RagService.QueryResult qr = rag.query(query);
                resp.setAnswer(qr.answer);
                resp.setSearchResults(toSearchResults(qr.results));
                onEvent.accept(StreamEvent.ragResult(resp.getSearchResults()));
            }
            default -> {
                String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                        "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
                resp.setAnswer(llm.chat(sp, histMsgs));
            }
        }

        if (cancelled.get()) resp.setInterrupted(true);

        stm.add("assistant", resp.getAnswer());
        infra.saveChatHistory("assistant", resp.getAnswer());

        // 异步：LLM 分类记忆写入（替代旧的 extractMemoryFromReply）
        memoryWriter.writeAfterReply(query, resp.getAnswer());

        // 异步合并：有图层时使用图感知合并
        new Thread(() -> {
            if (graphMem != null && graphMem.needConsolidation()) {
                LongTermMemory.ConsolidationResult result = graphMem.graphAwareConsolidate();
                syncConsolidationToDB(result);
            } else if (ltm.needConsolidation()) {
                LongTermMemory.ConsolidationResult result = ltm.consolidate();
                syncConsolidationToDB(result);
            }
        }).start();

        try {
            String eventData = mapper.writeValueAsString(Map.of("query", query, "mode", resp.getMode()));
            infra.publishEvent("agent.chat", eventData);
        } catch (Exception ignored) {}

        resp.setShortTermCount(stm.size());
        resp.setLongTermCount(ltm.size());
        resp.setPreferences(pref.getData());

        onEvent.accept(StreamEvent.done(resp));
        return resp;
    }

    public void cancel() { cancelled.set(true); }

    public void registerTool(Tool tool) { tools.put(tool.getName(), tool); }

    // ===== Accessors =====
    public Map<String, Tool> getTools() { return tools; }
    public ShortTermMemory getShortTermMemory() { return stm; }
    public LongTermMemory getLongTermMemory() { return ltm; }
    public PreferenceMemory getPreferences() { return pref; }
    public List<Snapshot> getSnapshots() {
        return snapshotManager == null ? new ArrayList<>() : snapshotManager.snapshots();
    }
    public RagService getRagService() { return rag; }
    public KGStore getKnowledgeGraph() { return kg; }
    public Sandbox getSandbox() { return sandbox; }

    // ===== Helpers =====

    private void runAsyncPreferenceExtraction(String query) {
        new Thread(() -> {
            Map<String, String> kvs = llm.extractPreferences(query);
            if (kvs == null || kvs.isEmpty()) return;
            pref.saveBatch(kvs);
            for (Map.Entry<String, String> e : kvs.entrySet()) {
                infra.savePreference("default", e.getKey(), e.getValue());
                String content = "用户" + e.getKey() + ": " + e.getValue();
                List<Double> emb = llm.embed(content);
                boolean added = storeMemory(content, 0.8, emb);
                if (added) {
                    String embJson = "null";
                    try { if (emb != null) embJson = mapper.writeValueAsString(emb); } catch (Exception ignored) {}
                    int pgId = infra.saveLongTermItem(content, 0.8, embJson);
                    syncMemoryPGID(pgId);
                }
            }
        }, "preference-extract").start();
    }

    private boolean storeMemory(String content, double importance, List<Double> emb) {
        if (graphMem != null) return graphMem.store(content, importance, emb).added();
        return ltm.store(content, importance, emb);
    }

    private void syncMemoryPGID(int pgId) {
        if (graphMem != null) graphMem.syncLastItemPGID(pgId);
        else ltm.syncLastItemPGID(pgId);
    }

    private String buildMemorySystemPrefix() {
        List<String> parts = new ArrayList<>();
        String prefCtx = pref.buildContext();
        if (!prefCtx.isEmpty()) parts.add(prefCtx);
        List<MemoryItem> ltmItems = ltm.getItems();
        if (!ltmItems.isEmpty()) {
            List<String> contents = ltmItems.stream().map(MemoryItem::getContent).toList();
            parts.add("【长期记忆】\n" + String.join("\n", contents));
        }
        return String.join("\n\n", parts);
    }

    private String buildMemorySystemPrefixWithCtx(String query) {
        List<String> parts = new ArrayList<>();
        String prefCtx = pref.buildContext();
        if (!prefCtx.isEmpty()) parts.add(prefCtx);

        List<Double> queryEmb = llm.embed(query);
        List<MemoryItem> recalled = (graphMem != null
                ? graphMem.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb)
                : ltm.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb));
        if (!recalled.isEmpty()) {
            List<String> contents = recalled.stream().map(MemoryItem::getContent).toList();
            parts.add("【相关记忆】\n" + String.join("\n", contents));
        }
        return String.join("\n\n", parts);
    }

    private Map<String, Tool> filterTools(List<String> names) {
        Map<String, Tool> result = new java.util.HashMap<>();
        for (String name : names) {
            if (tools.containsKey(name)) result.put(name, tools.get(name));
        }
        return result;
    }

    private void syncConsolidationToDB(LongTermMemory.ConsolidationResult result) {
        if (!result.deleteFromDB.isEmpty()) {
            infra.deleteLongTermItems(result.deleteFromDB);
            log.info("记忆合并：删除 {} 条（去重={}, 合并={}, 过期={}）",
                    result.deduped + result.merged + result.expired,
                    result.deduped, result.merged, result.expired);
        }
        for (MemoryItem item : result.updateInDB) {
            String embJson = "null";
            try { if (item.getEmbedding() != null) embJson = mapper.writeValueAsString(item.getEmbedding()); } catch (Exception ignored) {}
            infra.updateLongTermItem(item.getId(), item.getContent(), item.getImportance(), embJson);
        }
    }

    private void restoreFromDB() {
        Map<String, String> prefs = infra.loadPreferences("default");
        pref.saveBatch(prefs);

        List<InfrastructureService.LongTermRow> rows = infra.loadLongTermItems();
        for (InfrastructureService.LongTermRow row : rows) {
            MemoryItem item = new MemoryItem();
            item.setId(row.id); item.setContent(row.content); item.setImportance(row.importance);
            item.setEmbedding(row.embedding);
            if (row.createdAt != null) item.setCreatedAt(row.createdAt.toLocalDateTime());
            if (row.lastAccessed != null) item.setLastAccessed(row.lastAccessed.toLocalDateTime());
            if (row.category != null) item.setCategory(row.category);
            if (row.tags != null) item.setTags(row.tags);
            if (row.slotHint != null) item.setSlotHint(row.slotHint);
            ltm.storeItem(item);
        }

        int chatLimit = cfg.getMemory().getShortTermMaxTurns() * 2;
        List<InfrastructureService.ChatHistoryRow> history = infra.loadChatHistory(chatLimit);
        for (InfrastructureService.ChatHistoryRow h : history) {
            stm.add(h.role, h.content);
        }

        if (!prefs.isEmpty() || !rows.isEmpty() || !history.isEmpty()) {
            log.info("记忆恢复：{} 条偏好，{} 条长期记忆，{} 条聊天记录",
                    prefs.size(), rows.size(), history.size());
        }
    }

    private void restoreRAGFromDB() {
        List<InfrastructureService.ChunkRow> chunkRows = infra.loadAllRAGChunks();
        if (chunkRows.isEmpty()) return;
        List<com.agi.assistant.model.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkRows.size(); i++) {
            chunks.add(new com.agi.assistant.model.Chunk(i, chunkRows.get(i).content));
        }
        rag.restoreChunks(chunks);
        log.info("RAG chunks 恢复：{} 条", chunks.size());
    }

    private List<ChatResponse.SearchResultDto> toSearchResults(List<RagService.ScoredChunk> results) {
        if (results == null) return null;
        return results.stream()
                .map(r -> new ChatResponse.SearchResultDto(r.chunk, r.score))
                .toList();
    }

    /** 兼容签名保留（旧调用点已统一改用 TavilyClient.search）。 */
    static String tavilySearch(String query, String apiKey, String apiUrl) throws Exception {
        return TavilyClient.search(query, apiKey, apiUrl);
    }
}
