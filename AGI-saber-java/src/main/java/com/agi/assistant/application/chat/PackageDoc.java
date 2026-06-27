package com.agi.assistant.application.chat;

import com.agi.assistant.dto.ChatRequest;
import com.agi.assistant.dto.ChatResponse;
import com.agi.assistant.model.Tool;
import com.agi.assistant.service.agent.UnifiedAgentService;

import java.util.Locale;
import java.util.Map;

/**
 * 包文档（对应 Go internal/application/chat/doc.go）。
 *
 * <p>application/chat 是 DDD 应用层（用例编排层）。它把 interfaces.http 的请求映射
 * 为下面这套有清晰边界的协作：</p>
 *
 * <pre>
 *  ChatApplicationService.process(ChatRequest)
 *    │
 *    ├─ ChatRouter.decideMode               —— 选 chat/tool/rag/react
 *    │
 *    ├─ ChatContextBuilder.buildPrefix      —— Schema-driven 上下文装配
 *    │      （Profile / Planner / TaskMem / ToolState / Constraints / Recall）
 *    │
 *    ├─ 按 mode 分发到 UnifiedAgentService 内的 ReAct/Tool/RAG/Chat 处理器
 *    │
 *    └─ 异步：偏好提取、记忆写入、记忆合并、Kafka 事件发布
 * </pre>
 *
 * <p>当前阶段：UnifiedAgentService 仍承担大部分流程编排，本包提供 facade
 * 与可独立测试的 router / context_builder。后续可把 ReAct/Tool 模式
 * 实现也从 UnifiedAgentService 拆到本包内。</p>
 */
public final class PackageDoc {
    private PackageDoc() {}

    /** 简化签名说明：模式选择 + 工具过滤是 Router 的职责。 */
    @SuppressWarnings("unused")
    static String demoRoute(ChatRequest req, Map<String, Tool> available, boolean ragLoaded) {
        return ChatRouter.decideMode(
                req.getMessage(),
                req.isExplicit(),
                req.isUseRag(),
                req.getSelectedTools(),
                ragLoaded
        ).toLowerCase(Locale.ROOT);
    }

    /** 简化签名说明：调用引擎执行整轮处理。 */
    @SuppressWarnings("unused")
    static ChatResponse demoExecute(UnifiedAgentService engine, ChatRequest req) {
        return engine.processWithOptions(req.getMessage(), req);
    }
}
