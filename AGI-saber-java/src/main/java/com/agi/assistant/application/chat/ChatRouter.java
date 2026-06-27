package com.agi.assistant.application.chat;

import com.agi.assistant.model.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模式路由判断（对应 Go internal/application/chat/router.go）。
 *
 * 把基于关键词的启发式判断（needTool / needRAG / needReAct / needReActFromTools）
 * 抽出为纯函数，便于单元测试。
 */
public final class ChatRouter {

    private ChatRouter() {}

    /** 单一工具触发：时间 / 天气 / 搜索 / 查询 */
    public static boolean needTool(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return q.contains("几点") || q.contains("时间")
                || q.contains("天气") || q.contains("查")
                || q.contains("搜索") || q.contains("是什么");
    }

    /** 知识库已加载且本次不走工具/ReAct 时启用 RAG */
    public static boolean needRAG(String query, boolean ragLoaded) {
        return ragLoaded && !needTool(query) && !needReAct(query);
    }

    /** 当 query 涉及 2+ 个子需求时触发多步推理 */
    public static boolean needReAct(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        int count = 0;
        if (q.contains("时间") || q.contains("几点")) count++;
        if (q.contains("天气")) count++;
        if (q.contains("总结") || q.contains("汇总")) count++;
        if (q.contains("查") || q.contains("搜索")) count++;
        return count >= 2;
    }

    /** 在显式指定了工具集时直接走 ReAct 路径 */
    public static boolean needReActFromTools(String query, Map<String, Tool> ts) {
        return ts != null && !ts.isEmpty();
    }

    /** 选定模式：Explicit 模式时优先使用前端指定的 Tools/UseRAG，否则走规则。 */
    public static String decideMode(String query, boolean explicit, boolean useRag,
                                    List<String> selectedTools, boolean ragLoaded) {
        if (explicit) {
            if (selectedTools != null && !selectedTools.isEmpty()) {
                return "react";
            }
            if (useRag && ragLoaded) return "rag";
            return "chat";
        }
        if (needReAct(query)) return "react";
        if (needTool(query)) return "tool";
        if (needRAG(query, ragLoaded)) return "rag";
        return "chat";
    }
}
