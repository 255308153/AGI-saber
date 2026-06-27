package com.agi.assistant.service.rag;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.rag.HistoryMessage;
import com.agi.assistant.domain.rag.Reranker;
import com.agi.assistant.domain.rag.Rewriter;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.model.Chunk;
import com.agi.assistant.service.graph.ChunkRef;
import com.agi.assistant.service.graph.KGStore;
import com.agi.assistant.service.memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG Engine - integrates text splitting, hybrid retrieval, and answer generation.
 * 对应 Go internal/rag/rag.go
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final AppConfig cfg;
    private final HybridStore store;
    private final TextSplitter splitter;
    private final InfrastructureService infra;
    private KGStore kg;

    private boolean loaded = false;
    private BiFunction<String, String, String> generateFn;
    private Function<String, List<Double>> embedFn;
    private Rewriter rewriter;
    private Reranker reranker;

    public RagService(AppConfig cfg, HybridStore store, TextSplitter splitter, InfrastructureService infra) {
        this.cfg = cfg;
        this.store = store;
        this.splitter = splitter;
        this.infra = infra;
        this.splitter.setChunkSize(cfg.getRag().getChunkSize());
        this.splitter.setOverlap(cfg.getRag().getChunkOverlap());
    }

    public void setGenerateFn(BiFunction<String, String, String> fn) { this.generateFn = fn; }
    public void setEmbedFn(Function<String, List<Double>> fn) {
        this.embedFn = fn;
        this.store.setEmbedFn(fn);
    }
    public void setKGStore(KGStore kg) {
        this.kg = kg;
        this.store.setKGStore(kg);
    }
    /** 注入查询改写器；null 等价于关闭 */
    public void setRewriter(Rewriter r) { this.rewriter = r; }
    /** 注入精排器；null 等价于关闭 */
    public void setReranker(Reranker r) { this.reranker = r; }

    public boolean isLoaded() { return loaded; }
    public String getMode() { return store.getMode(); }

    public Map.Entry<Integer, String> ingest(String doc) {
        List<Chunk> chunks = splitter.split(doc);
        String docHash = store.index(chunks, doc);
        loaded = true;
        infra.publishEvent("rag.ingest",
                String.format("{\"chunk_count\":%d,\"mode\":\"%s\",\"doc_hash\":\"%s\"}",
                        chunks.size(), store.getMode(), docHash));
        // 异步建图
        if (kg != null && kg.available()) {
            List<ChunkRef> refs = new ArrayList<>();
            for (Chunk c : chunks) refs.add(new ChunkRef(c.getId(), c.getContent()));
            new Thread(() -> kg.indexDocument(docHash, refs), "kg-index").start();
        }
        return Map.entry(chunks.size(), docHash);
    }

    public void delete(String docHash) {
        store.delete(docHash);
        if (kg != null && kg.available()) kg.deleteDocument(docHash);
        // 重新检测是否还有 chunks
        loaded = !infra.loadAllRAGChunks().isEmpty();
    }

    public QueryResult query(String question) {
        return queryWithHistory(question, null);
    }

    /**
     * 带历史的查询：rewriter（history-aware + multi-query）→ 多查询并发检索 + RRF 合并
     * → reranker 精排 → small-to-big → LLM 合成（对应 Go rag.QueryWithHistory）。
     */
    public QueryResult queryWithHistory(String question, List<HistoryMessage> history) {
        if (!loaded) {
            return new QueryResult("知识库为空，请先上传文档。", Collections.emptyList());
        }

        // 1) Rewrite
        List<String> queries = new ArrayList<>();
        queries.add(question);
        if (rewriter != null) {
            List<String> rewritten = rewriter.rewrite(question, history);
            if (rewritten != null && !rewritten.isEmpty()) queries = rewritten;
        }

        // 2) 多查询合并（fetch 4× topK 给 reranker 留余量）
        int fetchK = reranker != null ? Math.max(cfg.getRag().getTopK() * 4, 10) : cfg.getRag().getTopK();
        List<ScoredChunk> results = searchMulti(queries, fetchK);

        // unavailable 模式：兜底 TF
        if (results.isEmpty() && "unavailable".equals(store.getMode())) {
            results = tfSearch(question, cfg.getRag().getTopK());
        }
        if (results.isEmpty()) {
            return new QueryResult("知识库中未找到相关内容。", Collections.emptyList());
        }

        // 3) Rerank
        if (reranker != null) {
            results = reranker.rerank(question, results, cfg.getRag().getTopK());
        } else if (results.size() > cfg.getRag().getTopK()) {
            results = new ArrayList<>(results.subList(0, cfg.getRag().getTopK()));
        }

        String context = results.stream()
                .map(r -> r.chunk.getContent())
                .collect(Collectors.joining("\n\n"));

        String answer;
        if (generateFn != null) {
            String systemPrompt = "你是一个基于知识库回答问题的助手。请仅根据提供的上下文内容回答问题，不要编造信息。如果上下文不足以回答，请说明。";
            // 给 LLM 的查询用第一条改写（独立化后的版本）
            String askQuery = queries.isEmpty() ? question : queries.get(0);
            String userMsg = String.format("上下文：\n%s\n\n问题：%s", context, askQuery);
            answer = generateFn.apply(systemPrompt, userMsg);
        } else {
            answer = "【知识库检索结果】\n" + context;
        }
        return new QueryResult(answer, results);
    }

    /**
     * 多查询并发执行 + 跨查询 RRF 合并（对应 Go rag.HybridStore.SearchMulti）。
     * 单查询时直接走 store.search。
     */
    public List<ScoredChunk> searchMulti(List<String> queries, int topK) {
        if (queries == null || queries.isEmpty()) return Collections.emptyList();
        if (queries.size() == 1) {
            List<HybridStore.SearchResult> hits = store.search(queries.get(0), topK);
            return toScored(hits);
        }
        // 并发检索每条 query，按 chunk 内容做 RRF 合并
        Map<String, double[]> rrf = new LinkedHashMap<>(); // contentHash -> [score, lastIdx]
        Map<String, Chunk> chunkMap = new HashMap<>();
        int k = cfg.getRag().getRrfConstantK() > 0 ? cfg.getRag().getRrfConstantK() : 60;

        for (String q : queries) {
            List<HybridStore.SearchResult> hits = store.search(q, topK);
            for (int i = 0; i < hits.size(); i++) {
                String content = hits.get(i).chunk.getContent();
                String key = content == null ? String.valueOf(hits.get(i).chunk.getId()) : content;
                rrf.computeIfAbsent(key, s -> new double[]{0, 0})[0] += 1.0 / (k + i + 1);
                chunkMap.putIfAbsent(key, hits.get(i).chunk);
            }
        }
        if (rrf.isEmpty()) return Collections.emptyList();

        List<Map.Entry<String, double[]>> sorted = new ArrayList<>(rrf.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        if (sorted.size() > topK) sorted = sorted.subList(0, topK);

        List<ScoredChunk> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : sorted) {
            out.add(new ScoredChunk(chunkMap.get(e.getKey()), e.getValue()[0]));
        }
        return out;
    }

    private List<ScoredChunk> toScored(List<HybridStore.SearchResult> hits) {
        List<ScoredChunk> out = new ArrayList<>();
        if (hits == null) return out;
        for (HybridStore.SearchResult r : hits) out.add(new ScoredChunk(r.chunk, r.score));
        return out;
    }

    public List<Chunk> getChunks() {
        List<InfrastructureService.ChunkRow> rows = infra.loadAllRAGChunks();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            chunks.add(new Chunk(i, rows.get(i).content));
        }
        return chunks;
    }

    public void restoreChunks(List<Chunk> chunks) {
        loaded = !chunks.isEmpty();
        store.restoreChunks(chunks);
    }

    // ============ TF 兜底搜索 ============

    private List<ScoredChunk> tfSearch(String query, int topK) {
        List<InfrastructureService.ChunkRow> rows = infra.loadAllRAGChunks();
        if (rows.isEmpty()) return Collections.emptyList();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) chunks.add(new Chunk(i, rows.get(i).content));

        Set<String> allTokens = new LinkedHashSet<>();
        List<String> queryTokens = LongTermMemory.tokenize(query);
        allTokens.addAll(queryTokens);
        for (Chunk c : chunks) allTokens.addAll(LongTermMemory.tokenize(c.getContent()));
        List<String> vocabList = new ArrayList<>(allTokens);
        Map<String, Integer> vocabIdx = new HashMap<>();
        for (int i = 0; i < vocabList.size(); i++) vocabIdx.put(vocabList.get(i), i);

        double[] qVec = new double[vocabList.size()];
        for (String t : queryTokens) {
            Integer idx = vocabIdx.get(t);
            if (idx != null) qVec[idx]++;
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            double[] cVec = new double[vocabList.size()];
            for (String t : LongTermMemory.tokenize(c.getContent())) {
                Integer idx = vocabIdx.get(t);
                if (idx != null) cVec[idx]++;
            }
            double sim = cosine(qVec, cVec);
            if (sim > 0) scored.add(new ScoredChunk(c, sim));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ============ Result types ============

    public static class ScoredChunk {
        public Chunk chunk;
        public double score;
        public ScoredChunk(Chunk chunk, double score) { this.chunk = chunk; this.score = score; }
    }

    public static class QueryResult {
        public String answer;
        public List<ScoredChunk> results;
        public QueryResult(String answer, List<ScoredChunk> results) {
            this.answer = answer; this.results = results;
        }
    }
}
