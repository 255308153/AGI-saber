package com.agi.assistant.domain.rag;

import com.agi.assistant.service.rag.RagService.ScoredChunk;

import java.util.List;

/**
 * 重排序器接口（对应 Go domain/rag.Reranker）。
 *
 * 在 RRF 融合之后、送给 LLM 合成之前插入一层精排：召回侧扩大候选量，
 * rerank 把候选打分排序后截回 topK，去掉 “相关但不够精确” 的噪声。
 *
 * 实现失败时应返回原始 results 截断到 topK，永不返回 null。
 */
public interface Reranker {
    List<ScoredChunk> rerank(String query, List<ScoredChunk> results, int topK);
}
