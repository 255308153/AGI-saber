package com.agi.assistant.application.chat;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.domain.promptctx.ContextAssembler;
import com.agi.assistant.domain.promptctx.Query;
import com.agi.assistant.domain.promptctx.RuntimeContext;
import com.agi.assistant.domain.promptctx.SourceRegistry;
import com.agi.assistant.domain.promptctx.Schemas;
import com.agi.assistant.domain.promptctx.source.ConstraintsSource;
import com.agi.assistant.domain.promptctx.source.PlannerSnapshot;
import com.agi.assistant.domain.promptctx.source.PlannerSource;
import com.agi.assistant.domain.promptctx.source.ProfileSource;
import com.agi.assistant.domain.promptctx.source.RecallSource;
import com.agi.assistant.domain.promptctx.source.TaskMemBuffer;
import com.agi.assistant.domain.promptctx.source.TaskMemSource;
import com.agi.assistant.domain.promptctx.source.ToolStateSource;
import com.agi.assistant.domain.promptctx.source.ToolStateTracker;
import com.agi.assistant.model.Tool;
import com.agi.assistant.service.llm.LlmService;
import com.agi.assistant.service.memory.GraphMemory;
import com.agi.assistant.service.memory.LongTermMemory;
import com.agi.assistant.service.memory.PreferenceMemory;
import com.agi.assistant.model.MemoryItem;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Schema-driven prompt 上下文构建器（对应 Go internal/application/chat/context_builder.go）。
 *
 * application 层在每轮推理前调用 {@link #buildPrefix(String, String)}，
 * 输出一段渲染好的 system prompt 前缀（用户画像 + 任务规划 + 任务记忆 +
 * 可用工具 + 硬性约束 + 相关回忆）。
 */
public class ChatContextBuilder {

    private final ContextAssembler assembler;
    private final TaskMemBuffer taskMem;
    private final ToolStateTracker toolTracker;
    private final LlmService llm;

    public ChatContextBuilder(AppConfig cfg,
                              LlmService llm,
                              PreferenceMemory pref,
                              LongTermMemory ltm,
                              GraphMemory graphMem,
                              Supplier<Map<String, Tool>> toolsRegistry,
                              Supplier<PlannerSnapshot> plannerProvider) {
        this.llm = llm;
        this.taskMem = new TaskMemBuffer(20);
        this.toolTracker = new ToolStateTracker(10);

        SourceRegistry registry = new SourceRegistry();
        registry.register(new ProfileSource(pref, ltm));
        registry.register(new PlannerSource(plannerProvider));
        registry.register(new TaskMemSource(taskMem));
        registry.register(new ToolStateSource(toolsRegistry, toolTracker));
        registry.register(ConstraintsSource.fromBuiltinValidator());
        // 优先用图记忆作 Recaller；图层不可用时退化到 LTM
        if (graphMem != null) {
            registry.register(new RecallSource(graphMem::recall));
        } else {
            registry.register(new RecallSource(ltm::recall));
        }
        this.assembler = new ContextAssembler(Schemas.defaults(), registry);
    }

    /** 装配并渲染当前 mode 下的 system prompt 前缀。 */
    public String buildPrefix(String query, String mode) {
        List<Double> emb = llm.embed(query);
        Query q = new Query(query, emb, "", mode);
        RuntimeContext rc = assembler.assemble(q);
        return rc.render();
    }

    /** 拼接记忆前缀到 base prompt 之前。 */
    public String buildSystemPrompt(String memPrefix, String basePrompt) {
        if (memPrefix == null || memPrefix.isEmpty()) return basePrompt;
        return memPrefix + "\n\n" + basePrompt;
    }

    public TaskMemBuffer taskMem() { return taskMem; }

    public ToolStateTracker toolTracker() { return toolTracker; }
}
