package com.agi.assistant.application.chat;

import com.agi.assistant.model.ConversationMessage;
import com.agi.assistant.service.memory.ShortTermMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史消息适配器（对应 Go application/chat/history.go）。
 *
 * <p>把 ShortTermMemory 中的对话历史转成 LLM 调用所需的 message 列表，
 * 并提供 system prompt 拼接的通用工具方法。</p>
 */
public final class ChatHistoryAdapter {

    private ChatHistoryAdapter() {}

    /** STM → LLM messages，并保证最后一条是当前 user 消息。 */
    public static List<Map<String, String>> buildHistory(ShortTermMemory stm, String query) {
        List<Map<String, String>> msgs = new ArrayList<>();
        for (ConversationMessage m : stm.getMessages()) {
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                msgs.add(Map.of("role", m.getRole(), "content", m.getContent()));
            }
        }
        if (msgs.isEmpty() || !msgs.get(msgs.size() - 1).get("content").equals(query)) {
            msgs.add(Map.of("role", "user", "content", query));
        }
        return msgs;
    }

    /** memPrefix + base 拼成 system prompt。 */
    public static String buildSystemPrompt(String memPrefix, String basePrompt) {
        if (memPrefix == null || memPrefix.isEmpty()) return basePrompt;
        return memPrefix + "\n\n" + basePrompt;
    }
}
