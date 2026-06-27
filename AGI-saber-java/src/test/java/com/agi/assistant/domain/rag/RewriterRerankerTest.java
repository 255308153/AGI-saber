package com.agi.assistant.domain.rag;

import com.agi.assistant.model.Chunk;
import com.agi.assistant.service.rag.RagService.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RewriterRerankerTest {

    @Test
    void rewriterReturnsSingletonWhenLLMNull() {
        Rewriter r = new LLMRewriter(null, 3);
        List<String> out = r.rewrite("你好", List.of());
        assertEquals(1, out.size());
        assertEquals("你好", out.get(0));
    }

    @Test
    void rewriterReturnsSingletonWhenNumQueriesLow() {
        Rewriter r = new LLMRewriter((sp, um) -> "irrelevant", 1);
        List<String> out = r.rewrite("你好", List.of());
        assertEquals(1, out.size());
    }

    @Test
    void rewriterParsesValidJson() {
        Rewriter r = new LLMRewriter(
                (sp, um) -> "{\"queries\":[\"独立查询\",\"变体一\",\"变体二\"]}",
                3);
        List<String> out = r.rewrite("原查询", List.of());
        assertEquals(3, out.size());
        assertTrue(out.contains("独立查询") || out.contains("原查询"));
    }

    @Test
    void rewriterFallsBackOnInvalidJson() {
        Rewriter r = new LLMRewriter((sp, um) -> "this is not json", 3);
        List<String> out = r.rewrite("原查询", List.of());
        assertEquals(1, out.size());
        assertEquals("原查询", out.get(0));
    }

    @Test
    void rerankerReturnsOriginalWhenLLMNull() {
        Reranker r = new LLMReranker(null, 200);
        List<ScoredChunk> in = sample(5);
        List<ScoredChunk> out = r.rerank("query", in, 3);
        assertEquals(3, out.size());
    }

    @Test
    void rerankerReordersByLLMScores() {
        Reranker r = new LLMReranker(
                (sp, um) -> "{\"scores\":[{\"idx\":0,\"score\":1},{\"idx\":1,\"score\":10},{\"idx\":2,\"score\":5}]}",
                200);
        List<ScoredChunk> in = sample(3);
        List<ScoredChunk> out = r.rerank("query", in, 3);
        assertEquals(3, out.size());
        // idx=1 应排第一（分数最高）
        assertEquals(in.get(1).chunk.getId(), out.get(0).chunk.getId());
    }

    @Test
    void rerankerFallsBackOnInvalidJson() {
        Reranker r = new LLMReranker((sp, um) -> "garbage", 200);
        List<ScoredChunk> in = sample(5);
        List<ScoredChunk> out = r.rerank("query", in, 3);
        assertEquals(3, out.size());
    }

    private static List<ScoredChunk> sample(int n) {
        List<ScoredChunk> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new ScoredChunk(new Chunk(i, "chunk content " + i), 0.5 - i * 0.05));
        }
        return list;
    }
}
