package com.agi.assistant.domain.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 用 LLM 做查询改写的默认实现（对应 Go domain/rag.LLMRewriter）。
 *
 * 受 numQueries 控制最终条数。generateFn 为 null 或 numQueries ≤ 1 时退化为 [original]，
 * 等价于关闭改写。
 */
public class LLMRewriter implements Rewriter {

    private static final Logger log = LoggerFactory.getLogger(LLMRewriter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_TEMPLATE = """
            你是检索系统的查询改写助手。给定用户当前问题和最近对话历史，你需要：
            1) 先把当前问题改写成一句**自包含的独立查询**（消除指代、补全省略，只用 query 本身就能让人看懂）。
            2) 再生成若干条**等价但措辞不同**的查询变体（同义词替换、抽象/具体切换、不同语序）。

            输出**严格 JSON**，不要任何说明文字、不要 markdown 代码块：
            {"queries": ["独立查询", "变体1", "变体2"]}

            约束：
            - 总条数严格等于 %d
            - 每条不超过 50 字
            - 不要编造历史中未出现的实体
            - 第一条必须可独立检索（不依赖历史）""";

    private final BiFunction<String, String, String> generateFn;
    private final int numQueries;

    public LLMRewriter(BiFunction<String, String, String> generateFn, int numQueries) {
        this.generateFn = generateFn;
        this.numQueries = numQueries <= 0 ? 3 : numQueries;
    }

    @Override
    public List<String> rewrite(String query, List<HistoryMessage> history) {
        if (query == null || query.trim().isEmpty()) return new ArrayList<>();
        String q = query.trim();
        if (generateFn == null || numQueries <= 1) {
            List<String> single = new ArrayList<>();
            single.add(q);
            return single;
        }

        StringBuilder hb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            hb.append("最近对话历史（按时间顺序）：\n");
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                HistoryMessage m = history.get(i);
                String role = m.getRole() == null || m.getRole().isEmpty() ? "user" : m.getRole();
                String content = m.getContent() == null ? "" : m.getContent().trim();
                if (content.codePointCount(0, content.length()) > 200) {
                    int[] cp = content.codePoints().limit(200).toArray();
                    content = new String(cp, 0, cp.length) + "…";
                }
                hb.append("[").append(role).append("] ").append(content).append("\n");
            }
        } else {
            hb.append("（无历史，直接改写当前问题）\n");
        }
        hb.append("\n当前问题：").append(q);

        String systemPrompt = String.format(SYSTEM_TEMPLATE, numQueries);
        String raw;
        try { raw = generateFn.apply(systemPrompt, hb.toString()); }
        catch (Exception e) {
            log.warn("Rewrite LLM 调用失败: {}", e.getMessage());
            return List.of(q);
        }
        List<String> parsed = parseRewriteJson(raw);
        if (parsed.isEmpty()) {
            log.warn("Query rewrite 解析失败，回退原查询（raw 前 100 字符: {}）",
                    raw == null ? "null" : raw.substring(0, Math.min(100, raw.length())));
            return List.of(q);
        }
        // 始终保留原查询，避免改写完全跑偏后召回归零
        parsed.add(q);
        List<String> deduped = dedupKeepOrder(parsed);
        if (deduped.size() > numQueries) deduped = deduped.subList(0, numQueries);
        return deduped;
    }

    private static List<String> parseRewriteJson(String raw) {
        if (raw == null) return new ArrayList<>();
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        try {
            JsonNode root = mapper.readTree(s);
            JsonNode queries = root.get("queries");
            if (queries == null || !queries.isArray()) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            for (JsonNode q : queries) {
                String text = q.asText("").trim();
                if (!text.isEmpty()) out.add(text);
            }
            return out;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static List<String> dedupKeepOrder(List<String> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            String key = s.toLowerCase().trim();
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(s);
        }
        return out;
    }
}
