package com.agi.assistant.application.chat;

import com.agi.assistant.dto.ChatRequest;
import com.agi.assistant.dto.ChatResponse;
import com.agi.assistant.service.agent.UnifiedAgentService;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 应用层入口：聊天对话用例（对应 Go internal/application/chat.UnifiedAgent）。
 *
 * <p>这是 DDD application 层的门面（facade）。内部把请求委托给已经被拆分到本包内的
 * 多个协作者：{@link ChatRouter}、{@link ChatContextBuilder}、{@link Planner}、
 * {@link ReActLoop}、{@link ToolModeHandler}、{@link ChatGenerator}、
 * {@link SnapshotManager}、{@link MemoryWriter}。</p>
 *
 * <p>由于 {@link UnifiedAgentService} 仍然持有 tools 注册表 + 4 类记忆 + KG/Sandbox
 * 等运行期可变状态，本类目前以薄壳形式委托过去，等 ChatSession 抽出后再彻底独立。</p>
 */
@Service
public class ChatApplicationService {

    private final UnifiedAgentService agent;

    public ChatApplicationService(UnifiedAgentService agent) {
        this.agent = agent;
    }

    /** 同步对话入口 */
    public ChatResponse process(ChatRequest req) {
        return agent.processWithOptions(req.getMessage(), req);
    }

    /**
     * 流式对话入口。每完成一个语义事件（start / mode / step / tool_call /
     * observation / rag_result / done）就回调一次 onEvent。
     *
     * <p>调用方（典型为 controller 的 SseEmitter）应在回调中把事件序列化推送给前端。</p>
     */
    public ChatResponse processStream(ChatRequest req, Consumer<StreamEvent> onEvent) {
        return agent.processStream(req.getMessage(), req, onEvent);
    }

    /** 取消所有 in-flight 请求 */
    public void cancel() {
        agent.cancel();
    }

    /** 暴露内部引擎，仅供需要直接访问 RAG/工具/记忆的接口层用例（status / upload）。 */
    public UnifiedAgentService engine() {
        return agent;
    }
}
