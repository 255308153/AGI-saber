package com.agi.assistant.application.chat;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.service.llm.LlmService;

import java.util.List;
import java.util.Map;

/**
 * 综合生成器（对应 Go application/chat/generator.go 中的 llmGenerate）。
 *
 * <p>把 ReAct 循环收集到的 observations 综合成最终回答；mock 模式时直接拼接。</p>
 */
public class ChatGenerator {

    private final AppConfig cfg;
    private final LlmService llm;

    public ChatGenerator(AppConfig cfg, LlmService llm) {
        this.cfg = cfg;
        this.llm = llm;
    }

    public String generate(String query, List<String> observations,
                           String memPrefix, List<Map<String, String>> histMsgs) {
        if (observations == null || observations.isEmpty()) {
            String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                    "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
            return llm.chat(sp, histMsgs);
        }
        if (!cfg.isRealLLM()) {
            return "综合查询结果：" + String.join("；", observations);
        }
        StringBuilder obs = new StringBuilder();
        for (int i = 0; i < observations.size(); i++) {
            obs.append(i + 1).append(". ").append(observations.get(i)).append("\n");
        }
        String genPrompt = String.format("""
                请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

                用户问题：%s

                工具执行结果：
                %s""", query, obs);
        String generatorBase = "你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。";
        if (memPrefix != null && !memPrefix.isEmpty()) {
            generatorBase = memPrefix + "\n\n" + generatorBase + "\n结合用户偏好，使回答更个性化。";
        }
        return llm.chat(generatorBase, List.of(Map.of("role", "user", "content", genPrompt)));
    }
}
