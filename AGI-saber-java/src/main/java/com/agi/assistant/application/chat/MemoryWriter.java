package com.agi.assistant.application.chat;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.service.llm.LlmService;
import com.agi.assistant.service.memory.GraphMemory;
import com.agi.assistant.service.memory.LongTermMemory;
import com.agi.assistant.service.memory.PreferenceMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回复后的记忆写入器（对应 Go application/chat/memory_writer.go）。
 *
 * <p>替代 {@code UnifiedAgentService.extractMemoryFromReply} 的扁平 KV 抽取，
 * 改为 LLM 分类后按类别写入：</p>
 * <ul>
 *   <li><b>identity</b> —— 用户身份（姓名/职业等），写 PreferenceMemory + LTM(0.9)</li>
 *   <li><b>preference</b> —— 用户偏好（喜好/语言/城市等），写 PreferenceMemory + LTM(0.7)</li>
 *   <li><b>tool_failure</b> —— 工具调用经验（避免重复犯错），仅写 LTM(0.6)</li>
 *   <li><b>policy</b> —— 用户给出的硬性约束/规则，仅写 LTM(0.8)</li>
 *   <li><b>general</b> —— 兜底类，写 LTM(0.5)</li>
 * </ul>
 */
public class MemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriter.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final AppConfig cfg;
    private final LlmService llm;
    private final PreferenceMemory pref;
    private final LongTermMemory ltm;
    private final GraphMemory graphMem;     // 可空
    private final InfrastructureService infra;

    public MemoryWriter(AppConfig cfg, LlmService llm, PreferenceMemory pref,
                        LongTermMemory ltm, GraphMemory graphMem, InfrastructureService infra) {
        this.cfg = cfg;
        this.llm = llm;
        this.pref = pref;
        this.ltm = ltm;
        this.graphMem = graphMem;
        this.infra = infra;
    }

    /** 异步入口：从助手回复中分类提取并写入。 */
    public void writeAfterReply(String query, String answer) {
        new Thread(() -> {
            try { writeNow(query, answer); }
            catch (Exception e) { log.warn("MemoryWriter 写入失败: {}", e.getMessage()); }
        }, "memory-writer").start();
    }

    /** 同步执行（便于单测）。 */
    public void writeNow(String query, String answer) {
        if (answer == null || answer.isEmpty() || !cfg.isRealLLM()) return;

        List<Classified> items = classify(answer);
        if (items.isEmpty()) return;

        for (Classified c : items) {
            persist(c);
        }
    }

    // ----------------- LLM 分类 -----------------

    private List<Classified> classify(String answer) {
        String prompt = "从下面这段AI助手的回复中，识别出值得长期记住的信息，并按类别分类。\n" +
                "可用类别（必须二选一）：\n" +
                "- identity：用户身份信息（姓名、职业、所在城市/国家、母语等不变属性）\n" +
                "- preference：用户偏好（喜好、习惯、风格、关注主题）\n" +
                "- tool_failure：本次工具调用中遇到的失败/教训（如工具不存在、参数错误、网络超时）\n" +
                "- policy：用户给出的硬性规则/约束（如「以后总用中文回答」「永远不用 sudo」）\n" +
                "- general：以上都不是但仍值得记住的客观事实\n\n" +
                "请输出 JSON：{\"items\":[{\"category\":\"...\",\"content\":\"...\",\"tags\":[\"...\"]}]}\n" +
                "如果没有值得记忆的内容，输出 {\"items\":[]}。只输出 JSON，不要解释。\n\n" +
                "回复：" + answer;

        String raw;
        try {
            raw = llm.chat("", List.of(Map.of("role", "user", "content", prompt)));
        } catch (Exception e) {
            log.debug("MemoryWriter.classify LLM 失败: {}", e.getMessage());
            return List.of();
        }
        if (raw == null) return List.of();
        raw = raw.trim().replace("```json", "").replace("```", "").trim();

        List<Classified> out = new ArrayList<>();
        try {
            JsonNode root = M.readTree(raw);
            JsonNode arr = root.get("items");
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode n : arr) {
                String cat = n.path("category").asText("general");
                String content = n.path("content").asText("");
                if (content.isEmpty()) continue;
                List<String> tags = new ArrayList<>();
                JsonNode t = n.get("tags");
                if (t != null && t.isArray()) {
                    for (JsonNode tg : t) {
                        String s = tg.asText("");
                        if (!s.isEmpty()) tags.add(s);
                    }
                }
                out.add(new Classified(normalizeCategory(cat), content, tags));
            }
        } catch (Exception e) {
            log.debug("MemoryWriter.classify 解析失败: {}", e.getMessage());
        }
        return out;
    }

    private static String normalizeCategory(String c) {
        if (c == null) return "general";
        String s = c.trim().toLowerCase();
        return switch (s) {
            case "identity", "preference", "tool_failure", "policy", "general" -> s;
            default -> "general";
        };
    }

    // ----------------- 写入 -----------------

    private void persist(Classified c) {
        double importance = importanceFor(c.category);
        String slotHint = slotHintFor(c.category);

        // identity / preference 同时回写 PreferenceMemory（保持与历史行为兼容）
        if ("identity".equals(c.category) || "preference".equals(c.category)) {
            String key = guessPrefKey(c.content);
            String value = c.content;
            if (key != null && !key.isEmpty()) {
                pref.save(key, value);
                infra.savePreference("default", key, value);
            }
        }

        List<Double> emb;
        try { emb = llm.embed(c.content); } catch (Exception e) { emb = null; }

        boolean added;
        if (graphMem != null) {
            added = graphMem.storeClassified(c.content, importance, emb, c.category, c.tags, slotHint).added();
        } else {
            added = ltm.storeClassified(c.content, importance, emb, c.category, c.tags, slotHint);
        }
        if (!added) return;

        // PG 同步
        String embJson = "null";
        try { if (emb != null) embJson = M.writeValueAsString(emb); } catch (Exception ignored) {}
        String tagsJson = null;
        try { if (c.tags != null && !c.tags.isEmpty()) tagsJson = M.writeValueAsString(c.tags); } catch (Exception ignored) {}
        int pgId = infra.saveLongTermItemClassified(c.content, importance, embJson, c.category, tagsJson, slotHint);
        if (graphMem != null) graphMem.syncLastItemPGID(pgId);
        else ltm.syncLastItemPGID(pgId);

        log.info("MemoryWriter: {} -> {} (importance={})", c.category, c.content, importance);
    }

    private static double importanceFor(String category) {
        return switch (category) {
            case "identity" -> 0.9;
            case "policy" -> 0.8;
            case "preference" -> 0.7;
            case "tool_failure" -> 0.6;
            default -> 0.5;
        };
    }

    /** 把 category 映射到 SlotKind 名（用于 promptctx 装配器优先填到对应槽位）。 */
    private static String slotHintFor(String category) {
        return switch (category) {
            case "identity", "preference" -> "Profile";
            case "policy" -> "Constraints";
            case "tool_failure" -> "ToolState";
            default -> null;
        };
    }

    /** 简单启发式：从 "用户姓名: 张三" 这样的内容拆出 key。Go 项目没有这一步；这里仅
     *  为了向后兼容旧的 PreferenceMemory 行为。 */
    private static String guessPrefKey(String content) {
        if (content == null) return null;
        int colon = content.indexOf(':');
        if (colon < 0) colon = content.indexOf('：');
        if (colon < 0) return null;
        String left = content.substring(0, colon).trim();
        if (left.startsWith("用户")) left = left.substring(2).trim();
        if (left.length() > 12) return null;
        return left.isEmpty() ? null : left;
    }

    private record Classified(String category, String content, List<String> tags) {}
}
