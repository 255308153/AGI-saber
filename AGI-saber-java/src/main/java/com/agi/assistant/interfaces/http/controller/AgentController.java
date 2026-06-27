package com.agi.assistant.interfaces.http.controller;

import com.agi.assistant.application.chat.ChatApplicationService;
import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.document.Document;
import com.agi.assistant.domain.document.ParseResult;
import com.agi.assistant.domain.document.WriteRequest;
import com.agi.assistant.dto.ChatRequest;
import com.agi.assistant.dto.ChatResponse;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.model.Chunk;
import com.agi.assistant.model.Snapshot;
import com.agi.assistant.model.Tool;
import com.agi.assistant.model.ToolParam;
import com.agi.assistant.service.agent.UnifiedAgentService;
import com.agi.assistant.service.document.DocumentLibraryService;
import com.agi.assistant.service.document.DocumentParser;
import com.agi.assistant.service.rag.RagService;
import com.agi.assistant.service.tools.ToolService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST API 入口（DDD interfaces 层；对应 Go internal/interfaces/http/handler.handler）。
 *
 * <p>不持有业务逻辑，只做：参数解析 → 调用 {@link ChatApplicationService} → 序列化响应。</p>
 *
 * 路由表：
 * <pre>
 *   POST /api/chat            统一对话入口（同步）
 *   POST /api/chat/stream     SSE 流式对话
 *   POST /api/chat/cancel     取消正在执行的对话
 *   POST /api/upload          上传文档到 RAG 知识库
 *   POST /api/docs/delete     按 docHash 删除文档
 *   POST /api/tools/mcp       动态注册 MCP 工具
 *   GET  /api/memory          查看三层记忆状态
 *   GET  /api/tools           列出所有可用工具
 *   GET  /api/snapshots       列出任务执行快照摘要
 *   GET  /api/status          系统状态与配置摘要
 * </pre>
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AgentController {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final ChatApplicationService chat;
    private final UnifiedAgentService agent;
    private final InfrastructureService infra;
    private final AppConfig cfg;
    private final ToolService toolService;
    private final DocumentParser parser;
    private final DocumentLibraryService library;

    public AgentController(ChatApplicationService chat,
                           UnifiedAgentService agent,
                           InfrastructureService infra,
                           AppConfig cfg,
                           ToolService toolService,
                           DocumentParser parser,
                           DocumentLibraryService library) {
        this.chat = chat;
        this.agent = agent;
        this.infra = infra;
        this.cfg = cfg;
        this.toolService = toolService;
        this.parser = parser;
        this.library = library;
    }

    /** POST /api/chat — 统一对话入口（同步模式，向后兼容） */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        return chat.process(req);
    }

    /** POST /api/chat/stream — SSE 流式对话入口（真流式：逐 step 推送事件） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            try {
                chat.processStream(req, ev -> {
                    try {
                        emitter.send(SseEmitter.event().name(ev.type()).data(ev.data()));
                    } catch (Exception ignored) {
                        // 客户端可能已断开，吞掉异常避免影响后续业务流程
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage())));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** POST /api/chat/cancel — 取消当前正在执行的任务 */
    @PostMapping("/chat/cancel")
    public Map<String, Object> chatCancel() {
        chat.cancel();
        return Map.of("ok", true, "message", "已发送取消信号");
    }

    /** POST /api/upload — 上传文档到 RAG 知识库（JSON: content 字符串） */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody Map<String, String> req) {
        String content = req.get("content");
        if (content == null || content.isEmpty()) return Map.of("error", "content is required");
        Map.Entry<Integer, String> result = agent.getRagService().ingest(content);
        return Map.of(
                "chunk_count", result.getKey(),
                "doc_hash", result.getValue(),
                "chunks", agent.getRagService().getChunks()
        );
    }

    /**
     * POST /api/upload/file — multipart 上传（支持 PDF / txt / md）。
     *
     * <p>路径：{@link DocumentParser} 解析 → 写入 {@link DocumentLibraryService}（来源 user_upload）→
     * 同步 ingest 到 RAG。返回解析摘要 + RAG ingest 结果 + 创建出的文档 id。</p>
     */
    @PostMapping(value = "/upload/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return Map.of("error", "file is required");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            return Map.of("error", "failed to read file: " + e.getMessage());
        }
        ParseResult pr;
        try {
            pr = parser.parseBytes(file.getOriginalFilename(),
                    file.getContentType(), data);
        } catch (DocumentParser.PdfNeedsOcrException e) {
            ParseResult r = e.getResult();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.getMessage());
            out.put("needs_ocr", true);
            out.put("pages", r == null ? 0 : r.pages);
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }

        // 落库到本地文档库 + ingest 到 RAG（来源标记为 user_upload）
        String title = file.getOriginalFilename() == null ? pr.parser + " upload" : file.getOriginalFilename();
        WriteRequest req = new WriteRequest(title, "upload", Document.SOURCE_UPLOAD, "user",
                pr.content,
                pr.content.length() > 180 ? pr.content.substring(0, 180) + "..." : pr.content,
                new LinkedHashMap<>(Map.of(
                        "parser", pr.parser, "pages", pr.pages,
                        "text_chars", pr.textChars, "needs_ocr", pr.needsOCR)));
        DocumentLibraryService.Result res = library.writeDocument(req, true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", pr.filename);
        out.put("content_type", pr.contentType);
        out.put("parser", pr.parser);
        out.put("pages", pr.pages);
        out.put("text_chars", pr.textChars);
        out.put("needs_ocr", pr.needsOCR);
        out.put("document_id", res.document.getId());
        out.put("version_id", res.version.getId());
        if (res.ingestChunks != null) {
            out.put("chunk_count", res.ingestChunks);
            out.put("doc_hash", res.ingestDocHash);
        }
        return out;
    }

    /** GET /api/documents — 文档库列表 */
    @GetMapping("/documents")
    public List<Document> listDocuments() {
        return library.list();
    }

    /** GET /api/documents/{id} — 取单个文档（含最新版本正文） */
    @GetMapping("/documents/{id}")
    public Map<String, Object> getDocument(@PathVariable("id") String id) {
        var dv = library.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", dv.document);
        out.put("version", dv.version);
        return out;
    }

    /** POST /api/documents — 由前端直接写入新文档（标题 / 正文 / docType / ingestToRAG） */
    @PostMapping("/documents")
    public Map<String, Object> writeDocument(@RequestBody Map<String, Object> req) {
        String title = (String) req.getOrDefault("title", "");
        String content = (String) req.getOrDefault("content_md", "");
        String docType = (String) req.getOrDefault("doc_type", "note");
        boolean ingest = Boolean.TRUE.equals(req.get("ingest_to_rag"));
        WriteRequest wr = new WriteRequest(title, docType, Document.SOURCE_UPLOAD, "user",
                content, "", new LinkedHashMap<>());
        DocumentLibraryService.Result res = library.writeDocument(wr, ingest);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", res.document);
        out.put("version", res.version);
        out.put("created", res.created);
        if (res.ingestChunks != null) {
            out.put("chunk_count", res.ingestChunks);
            out.put("doc_hash", res.ingestDocHash);
        }
        return out;
    }

    /** POST /api/docs/delete — 删除指定文档的所有 chunks */
    @PostMapping("/docs/delete")
    public Map<String, Object> docsDelete(@RequestBody Map<String, String> req) {
        String docHash = req.get("doc_hash");
        if (docHash == null || docHash.isEmpty()) return Map.of("error", "doc_hash is required");
        agent.getRagService().delete(docHash);
        return Map.of("ok", true, "doc_hash", docHash);
    }

    /** GET /api/memory — 查看三层记忆状态 */
    @GetMapping("/memory")
    public Map<String, Object> memory() {
        return Map.of(
                "short_term", agent.getShortTermMemory().getMessages(),
                "long_term", agent.getLongTermMemory().getItems(),
                "preference", agent.getPreferences().getData()
        );
    }

    /** GET /api/tools — 列出所有可用工具 */
    @GetMapping("/tools")
    public List<Map<String, Object>> toolsList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : agent.getTools().values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", t.getName());
            info.put("description", t.getDescription());
            if (t.isMcp()) info.put("is_mcp", true);
            if (t.getParameters() != null && !t.getParameters().isEmpty()) {
                info.put("params", t.getParameters());
            }
            list.add(info);
        }
        return list;
    }

    /** POST /api/tools/mcp — 动态注册一个 MCP 工具 */
    @PostMapping("/tools/mcp")
    public Map<String, Object> registerMCPTool(@RequestBody Map<String, Object> req) {
        String name = (String) req.get("name");
        String description = (String) req.get("description");
        String endpoint = (String) req.get("endpoint");
        if (name == null || name.isEmpty() || endpoint == null || endpoint.isEmpty()) {
            return Map.of("error", "name and endpoint are required");
        }
        List<ToolParam> params = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> paramsList = (List<Map<String, Object>>) req.get("params");
        if (paramsList != null) {
            for (Map<String, Object> p : paramsList) {
                params.add(new ToolParam(
                        (String) p.get("name"),
                        (String) p.get("type"),
                        (String) p.get("description"),
                        Boolean.TRUE.equals(p.get("required"))
                ));
            }
        }
        Tool tool = toolService.createMCPTool(name, description != null ? description : "", endpoint, params);
        agent.registerTool(tool);
        return Map.of("ok", true, "name", name);
    }

    /** GET /api/snapshots — 列出任务执行快照摘要 */
    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        List<Snapshot> snaps = agent.getSnapshots();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            Snapshot snap = snaps.get(i);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("index", i);
            info.put("timestamp", snap.getTimestamp());
            info.put("steps", snap.getState().getSteps() != null ? snap.getState().getSteps().size() : 0);
            result.add(info);
        }
        return result;
    }

    /** GET /api/status — 系统状态与配置摘要 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        RagService ragService = agent.getRagService();
        List<Chunk> chunks = ragService.getChunks();
        List<Map<String, Object>> chunkPreviews = new ArrayList<>();
        for (Chunk c : chunks) {
            String preview = c.getContent();
            if (preview != null && preview.length() > 60) preview = preview.substring(0, 60) + "...";
            chunkPreviews.add(Map.of("id", c.getId(), "content", preview));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rag_loaded", ragService.isLoaded());
        result.put("rag_mode", ragService.getMode());
        result.put("rag_chunks", chunkPreviews);
        result.put("short_term_count", agent.getShortTermMemory().size());
        result.put("long_term_count", agent.getLongTermMemory().size());
        result.put("preferences", agent.getPreferences().getData());
        result.put("tools_count", agent.getTools().size());
        result.put("llm_model", cfg.getLlm().getModel());
        result.put("embedding_model", cfg.getEmbedding().getModel());
        result.put("is_mock", !cfg.isRealLLM());
        result.put("infrastructure", infra.getStatus());
        return result;
    }
}
