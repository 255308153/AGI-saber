package com.agi.assistant.domain.rag;

import java.util.List;

/**
 * 查询改写器接口（对应 Go domain/rag.Rewriter）。
 *
 * 解决两类问题：
 *   1. 多轮指代 / 省略：用 STM 历史把 “那个再展开讲讲” 改写成自包含的独立查询；
 *   2. 单一查询召回偏窄：让 LLM 生成 N 条等价但措辞不同的查询，多查询并发检索后用 RRF 合并。
 *
 * 实现失败时应返回 [original]（永不返回空切片），让上层无感降级。
 */
public interface Rewriter {
    List<String> rewrite(String query, List<HistoryMessage> history);
}
