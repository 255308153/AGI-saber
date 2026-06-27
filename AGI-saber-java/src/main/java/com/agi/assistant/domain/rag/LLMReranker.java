package com.agi.assistant.domain.rag;

import com.agi.assistant.service.rag.RagService.ScoredChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 用一次 LLM listwise 调用对所有候选打分（0~10）（对应 Go domain/rag.LLMReranker）。
 *
 * 解析失败一律回退到原始顺序，永不让 rerank 阻塞主链路。
 */
public class LLMReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LLMReranker.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            你是检索系统的精排器。给定用户问题和若干候选段落（每条带编号 idx），
            判断每条段落对回答该问题的**相关性 + 信息密度**，给 0~10 的整数分。

            打分准则：
            - 10：直接回答了问题
            - 7~9：包含明确相关事实
            - 4~6：弱相关 / 部分相关
            - 1~3：仅出现共现关键词，不能用来回答
            - 0：无关 / 噪声

            输出**严格 JSON**，不要任何说明文字、不要 markdown 代码块：
            {"scores": [{"idx": 0, "score": 9}, {"idx": 1, "score": 3}, ...]}

            约束：
            - scores 数量严格等于候选数量
            - score 是 0~10 的整数
            - 不依赖你自己的知识，只看给出的段落""";

    private final BiFunction<String, String, String> generateFn;
    private final int previewLen;

    public LLMReranker(BiFunction<String, String, String> generateFn, int previewLen) {
        this.generateFn = generateFn;
        this.previewLen = previewLen <= 0 ? 200 : previewLen;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> results, int topK) {
        if (generateFn == null || results == null || results.isEmpty()) {
            return truncate(results, topK);
        }
        if (results.size() == 1) return new ArrayList<>(results);

        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(query).append("\n\n候选段落：\n");
        for (int i = 0; i < results.size(); i++) {
            String preview = results.get(i).chunk.getContent();
            if (preview != null && preview.codePointCount(0, preview.length()) > previewLen) {
                int[] cp = preview.codePoints().limit(previewLen).toArray();
                preview = new String(cp, 0, cp.length) + "…";
            }
            sb.append("[").append(i).append("] ").append(preview == null ? "" : preview).append("\n");
        }

        String raw;
        try { raw = generateFn.apply(SYSTEM_PROMPT, sb.toString()); }
        catch (Exception e) {
            log.warn("Rerank LLM 调用失败: {}", e.getMessage());
            return truncate(results, topK);
        }
        Map<Integer, Double> scores = parseRerankJson(raw);
        if (scores.isEmpty()) {
            log.warn("Rerank 解析失败，回退原顺序（raw 前 100 字符: {}）",
                    raw == null ? "null" : raw.substring(0, Math.min(100, raw.length())));
            return truncate(results, topK);
        }

        // 以 LLM 分数为主排序键，原 RRF score 作 tiebreaker
        record Scored(int idx, double llm, double rrf, ScoredChunk hr) {}
        List<Scored> pool = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            double llm = scores.getOrDefault(i, -1.0);
            pool.add(new Scored(i, llm, results.get(i).score, results.get(i)));
        }
        pool.sort(Comparator.<Scored>comparingDouble(s -> s.llm).reversed()
                .thenComparing(Comparator.<Scored>comparingDouble(s -> s.rrf).reversed()));

        List<ScoredChunk> out = new ArrayList<>(pool.size());
        for (Scored p : pool) {
            ScoredChunk reranked = new ScoredChunk(p.hr.chunk, p.llm >= 0 ? p.llm / 10.0 : p.hr.score);
            out.add(reranked);
        }
        return truncate(out, topK);
    }

    private static List<ScoredChunk> truncate(List<ScoredChunk> results, int topK) {
        if (results == null) return new ArrayList<>();
        if (topK > 0 && results.size() > topK) return new ArrayList<>(results.subList(0, topK));
        return new ArrayList<>(results);
    }

    private static Map<Integer, Double> parseRerankJson(String raw) {
        if (raw == null) return new HashMap<>();
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        Map<Integer, Double> map = new HashMap<>();
        try {
            JsonNode root = mapper.readTree(s);
            JsonNode arr = root.get("scores");
            if (arr == null || !arr.isArray()) return map;
            for (JsonNode it : arr) {
                int idx = it.has("idx") ? it.get("idx").asInt(-1) : -1;
                double score = it.has("score") ? it.get("score").asDouble(-1) : -1;
                if (idx >= 0) map.put(idx, score);
            }
        } catch (Exception ignored) {}
        return map;
    }
}
