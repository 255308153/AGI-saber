# DDD 重构 + 功能补齐说明

> 本文档说明本次对 `AGI-assistant-java` 进行的 **DDD 分层重构** 与 **功能补齐**，
> 与同仓 Go 版本（`/AGI-assistant`）对齐。

## 1. 新目录结构（DDD 四层）

```
com/agi/assistant/
├── AgiAssistantApplication.java        Spring Boot 入口
│
├── interfaces/                         接口层（承接外部请求）
│   └── http/
│       └── controller/
│           └── AgentController.java    REST API（10 个端点）
│
├── application/                        应用层（用例编排）
│   └── chat/
│       ├── ChatApplicationService.java 聊天用例门面
│       ├── ChatRouter.java             模式路由（chat/tool/rag/react）
│       ├── ChatContextBuilder.java     Schema-driven 上下文装配封装
│       └── PackageDoc.java             包结构与协作图说明
│
├── domain/                             领域层（核心业务规则）
│   ├── promptctx/                      Schema-driven 上下文装配（NEW）
│   │   ├── ContextAssembler.java       并发装配 + 全局预算裁剪
│   │   ├── SourceRegistry.java         按 SlotKind 分组的 source 注册
│   │   ├── Slot / SlotKind / SlotFilter / FilledSlot
│   │   ├── ContextItem / ContextSource / Query
│   │   ├── RuntimeContext / RuntimeContextSchema / Schemas
│   │   └── source/
│   │       ├── ProfileSource           身份与偏好
│   │       ├── PlannerSource           当前任务规划状态
│   │       ├── TaskMemSource           任务步骤观察缓冲
│   │       ├── ToolStateSource         可用工具 + 近期调用
│   │       ├── ConstraintsSource       沙箱安全政策
│   │       └── RecallSource            语义召回兜底
│   │
│   └── rag/                            RAG 增强（NEW）
│       ├── Rewriter / LLMRewriter      history-aware + multi-query 改写
│       ├── Reranker / LLMReranker      LLM listwise 精排
│       └── HistoryMessage              避免反向依赖 memory 包
│
├── infrastructure/                     基础设施层
│   ├── InfrastructureService.java      （legacy 单体；后续会按 platform/persistence 拆分）
│   ├── eventbus/                       NEW
│   │   ├── EventBus                    抽象接口
│   │   └── KafkaEventBus               桥接 InfrastructureService 现有 Kafka
│   └── tool/                           NEW
│       ├── McpTool                     MCP HTTP 调用适配器
│       └── TavilyClient                Tavily Search API 客户端
│
├── service/                            Spring Service（legacy；后续逐步合并到 domain/application）
│   ├── agent/UnifiedAgentService       核心调度器（与 Go agent.UnifiedAgent 对应）
│   ├── llm/                            LLM 客户端
│   ├── memory/{shortterm,longterm,preference,graph}
│   ├── graph/                          知识图谱（KGStore / Neo4jStore / Extractor）
│   ├── rag/                            HybridStore / TextSplitter / RagService（已接入 Rewriter/Reranker）
│   ├── sandbox/                        Validator + Executor（Docker/Local/Mock）
│   └── tools/                          ToolService / ExecCommandTool
│
├── model/                              领域对象（POJO；和 dto 区分）
└── dto/                                请求/响应 DTO
```

## 2. 本次新增功能（与 Go 对齐）

### A. `domain/promptctx/*`（11 + 11 = 11 文件）
Schema-driven Runtime Context Assembly：每轮推理前根据 Mode 选取
RuntimeContextSchema（认知槽位编排），并发调注册的 ContextSource 填充各槽位，
最后做全局字符预算裁剪。

涵盖的 6 个槽位：Profile / Planner / TaskMem / ToolState / Constraints / Recall。

```java
SourceRegistry reg = new SourceRegistry();
reg.register(new ProfileSource(pref, ltm));
reg.register(new PlannerSource(plannerProvider));
reg.register(new TaskMemSource(taskMem));
reg.register(new ToolStateSource(toolsRegistry, toolTracker));
reg.register(ConstraintsSource.fromBuiltinValidator());
reg.register(new RecallSource(graphMem != null ? graphMem::recall : ltm::recall));

ContextAssembler assembler = new ContextAssembler(Schemas.defaults(), reg);
String memPrefix = assembler.assemble(new Query(query, emb, taskId, mode)).render();
```

### B. `domain/rag/Rewriter` & `Reranker`（NEW）
- **LLMRewriter**：history-aware + multi-query 改写。把多轮指代/省略改成自包含查询，
  再让 LLM 生成 N 条等价但措辞不同的查询变体；多查询并发检索后用 RRF 合并，
  显著提升召回覆盖率。
- **LLMReranker**：listwise LLM 精排。fetchK = topK × 4 召回更多候选，
  rerank 把候选打分排序后截回 topK，去掉 “相关但不够精确” 的噪声。

新增配置项（`app.rag.*`）：
```yaml
rag:
  rewrite:
    enabled: true
    num_queries: 3   # 含原查询在内的目标改写条数
  rerank:
    enabled: true
    preview_len: 200 # 给 reranker 看的每条候选最大字符数
```

`RagService` 新增 `queryWithHistory(question, history)` 与 `searchMulti(queries, topK)`。

### C. `application/chat/*`（NEW）
DDD 应用层门面 + 路由器 + 上下文构建器。当前阶段保留 `UnifiedAgentService`
作为内核，application.chat 提供：
- `ChatApplicationService` —— `controller` 直接依赖的应用门面
- `ChatRouter` —— 把 needTool/needRAG/needReAct 的关键词规则抽出为可单测的纯函数
- `ChatContextBuilder` —— 把 promptctx 装配器 + 6 类 source 一起组装好的 facade

### D. `infrastructure/{eventbus,tool}/*`（NEW）
- `EventBus` 接口 + `KafkaEventBus` 实现：把事件总线从单体
  `InfrastructureService` 中独立出来，application 层只依赖接口。
- `McpTool.create(...)` / `TavilyClient.search(...)`：把工具实现按 Go 项目
  `infrastructure/tool/{mcp,tavily}.go` 的方式拆出来；`UnifiedAgentService.tavilySearch`
  与 `ToolService.createMCPTool` 都已改为薄包装，调用新模块。

### E. `interfaces/http/controller/*`
Controller 从 `controller/` 移到 `interfaces/http/controller/`，依赖
`ChatApplicationService` 而非直接 `UnifiedAgentService`。

## 3. 与 Go 项目的差异（剩余 Gap）

下表列出 Go 项目已有、本次 Java 重构 **未** 完整对齐的部分（建议后续迭代补齐）：

| Go 模块 | Java 现状 | 后续工作 |
|---------|----------|---------|
| `application/chat/{mode_react,mode_tool,planner,context_builder,memory_writer,init_sandbox,restore,cancel,accessor,status,process,types}.go` 13 文件 | `UnifiedAgentService` 单类承担。已抽 `ChatRouter` / `ChatContextBuilder` / `ChatApplicationService` | 把 ReAct 循环、Planner、ToolMode、SnapshotManager、CancelTokens 各自拆为独立类 |
| `infrastructure/persistence/{chathistory,preference,longterm,snapshot,ragchunk}` | `InfrastructureService` 单类承担所有 PG 访问 | 拆为 5 个 `*Repository` Bean（Spring Data JDBC 或手写 JdbcTemplate） |
| `infrastructure/platform/{postgres,milvus,es,kafka,neo4j}` | 全部混在 `InfrastructureService` 里 | 拆为 5 个 `*Connector` Bean，每路独立失败降级 |
| `infrastructure/sandbox/{docker,local,mock,factory}` | 都在 `service.sandbox` 下 | 把具体 Executor 实现（Docker/Local/Mock）移到 `infrastructure.sandbox`，domain 只留接口 |
| `application/chat/process.go` 的 SSE 流式 | 当前 SSE 以非流式消息打包 done 事件 | 新增 `chatStreamReal()` 在 mode_react 内逐 step 推送 |
| `application/chat/memory_writer.go` 的 LLM 分类（identity/preference/tool_failure/policy） | `UnifiedAgentService.extractMemoryFromReply` 只做扁平 KV 写入 | 加 `classifyMemoryContent()` + `LongTermMemory.storeClassified(...)`（需扩展 MemoryItem） |
| `domain/memory/longterm.RecallByFilter` + 类别过滤 | `LongTermMemory.recall(query, topK, emb)` 不支持 category 过滤 | 给 MemoryItem 加 `category/tags/slotHint`，给 LTM 加 `recallByFilter(...)` |

## 4. 编译验证

```sh
mvn -B clean compile
# BUILD SUCCESS  (83 source files compiled to target/classes)
```

## 5. 启用 Rewriter & Reranker

在 `src/main/resources/application.yml` 中开启即可生效：

```yaml
app:
  rag:
    rewrite:
      enabled: true
      num_queries: 3
    rerank:
      enabled: true
      preview_len: 200
```

无需任何业务代码改动。

---

## 6. 续作（2026-06）：第 3 节列出的 7 项 Gap 已全部完成

本次续作把上一节遗留的 7 项 Gap 一次做完。以下按交付物组织：

### A. 拆 InfrastructureService 平台连接器（Gap #2.platform）
新增 `infrastructure/platform/` 包，5 个独立 `@Component` Bean：
| 类 | 职责 |
|---|---|
| `PostgresConnector` | PG 连接 + 启动建表 + 失败降级 |
| `MilvusConnector` | Milvus 客户端 + RAG collection 初始化（含维度变更自动重建） |
| `ESConnector` | ES 客户端 + RAG 索引初始化 |
| `KafkaConnector` | Kafka producer + `publish(eventType, payload)` 兼容降级到日志 |
| `Neo4jConnector` | Neo4j 状态汇报（Driver 仍在 KGStore 内管理；本 Bean 仅暴露 `/api/status`） |

`KafkaEventBus` 改为直接依赖 `KafkaConnector`，application 层与 Kafka 完全解耦。

### B. 拆 InfrastructureService 持久化（Gap #2.persistence）
新增 `infrastructure/persistence/` 包，5 个独立 `@Repository` Bean：
- `PreferenceRepository` —— `save(userId, key, value)` / `loadAll(userId)`
- `LongTermRepository` —— `save / saveClassified / loadAll / update / deleteAll`，**支持 `category / tags / slot_hint` 三个新列**
- `ChatHistoryRepository` —— `save(role, content)` / `load(limit)`
- `SnapshotRepository` —— `save(taskId, stateJson)`
- `RagChunkRepository` —— `save / loadAll / loadByIds / deleteByDocHash`

`InfrastructureService` 退化为兼容门面：保留全部 16 个 public 方法签名（以避免 controller / agent 等调用点全部需要一次性改动），所有方法委托到上述 10 个 Bean。

PG DDL 通过 `ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS ...` 在启动时自动迁移，已上线数据无需停机。

### C. sandbox 实现下沉（Gap #4）
| 旧位置 | 新位置 |
|---|---|
| `service/sandbox/{Executor, Validator, Sandbox, ExecRequest, ExecResult, ValidationResult, RiskLevel}` | `domain/sandbox/...`（接口 + 值对象 + 聚合）|
| `service/sandbox/{DockerSandbox, LocalSandbox, MockSandbox}` | `infrastructure/sandbox/...` |
| `Sandbox.build(...)` 静态工厂 | `infrastructure/sandbox/SandboxFactory.build(...)` |

`service/sandbox/` 目录已删除。`UnifiedAgentService.initSandbox` 改用 `SandboxFactory.build(...)`，`ExecCommandTool` / `ConstraintsSource` 的 import 同步更新。

### D. UnifiedAgentService 拆分（Gap #1）
854 行的上帝类瘦身到 ~430 行，仅保留：装配（PostConstruct）+ 入口（process / processStream）+ accessor。核心循环和模式处理逻辑全部抽到 `application/chat/` 下：
| 新类 | 职责 |
|---|---|
| `Planner` | 任务规划（LLM JSON + 规则降级） |
| `ReActLoop` | ReAct 多步循环（同步 + 流式两条路径） |
| `ToolModeHandler` | 单步工具模式 |
| `ChatGenerator` | 综合生成（observations → 自然回答） |
| `SnapshotManager` | 快照持久化 + 中断摘要 |
| `MemoryWriter` | 回复后的记忆 LLM 分类与写入 |
| `ChatHistoryAdapter` | STM → LLM 消息列表 |
| `PreferenceFiller` | 偏好填参（城市/时区/姓名/语言/国家） |
| `StreamEvent` | SSE 流式事件值对象 |

### E. SSE 真流式（Gap #5）
- `application/chat/StreamEvent` —— 8 种事件类型（start / mode / step / tool_call / observation / rag_result / token / done / error）
- `ReActLoop.runStream(... Consumer<StreamEvent> onEvent)` —— ReAct 路径每 step 推送
- `ChatApplicationService.processStream(req, onEvent)` —— application 层流式入口
- `AgentController.chatStream` —— 改为「调用 processStream，逐事件 emitter.send」

旧版本只发 start / done 两帧，新版本根据模式逐步推送中间过程。

### F. memory_writer LLM 分类（Gap #6）
`application/chat/MemoryWriter` 取代旧的 `extractMemoryFromReply`：
- 用 LLM 把回复内容分类为 5 类：`identity / preference / tool_failure / policy / general`
- importance 按类别选取（identity 0.9 / policy 0.8 / preference 0.7 / tool_failure 0.6 / general 0.5）
- `slotHint` 自动映射到 `Profile / Constraints / ToolState` 三个 promptctx 槽位
- identity / preference 类同时回写 `PreferenceMemory`，保持与历史行为兼容

### G. LongTermMemory.recallByFilter（Gap #7）
- `MemoryItem` 新增 3 个字段：`category` / `tags` / `slotHint`，构造函数保持向后兼容
- `LongTermMemory.storeClassified(content, importance, embedding, category, tags, slotHint)` —— 复用现有 dedup 逻辑，命中重复时合并 tags、覆盖更具体的 category
- `LongTermMemory.recallByFilter(query, embedding, LongTermFilter)` —— 支持 4 个过滤维度：categories / requiredTags / minScore / maxAgeHours
- `GraphMemory.storeClassified` 同步增加，沿用图节点/边构建

### H. 文件清单总览（44 个文件）
**新增（25 个）**：
- `application/chat/{Planner, ReActLoop, ToolModeHandler, ChatGenerator, SnapshotManager, MemoryWriter, ChatHistoryAdapter, PreferenceFiller, StreamEvent}` (9)
- `domain/sandbox/{Executor, Validator, Sandbox, ExecRequest, ExecResult, ValidationResult, RiskLevel}` (7)
- `infrastructure/sandbox/{DockerSandbox, LocalSandbox, MockSandbox, SandboxFactory}` (4)
- `infrastructure/platform/{Postgres, Milvus, ES, Kafka, Neo4j}Connector` (5)
- `infrastructure/persistence/{Preference, LongTerm, ChatHistory, Snapshot, RagChunk}Repository` (5)
- `service/memory/LongTermFilter` (1)（注：以上累计 31 个新增；删除项见下）

**修改（13 个）**：
- `service/agent/UnifiedAgentService` —— 854 → ~430 行
- `infrastructure/InfrastructureService` —— 715 → ~330 行（变成纯门面）
- `service/memory/{LongTermMemory, GraphMemory}` —— 加 storeClassified / recallByFilter
- `model/MemoryItem` —— 加 3 字段
- `application/chat/ChatApplicationService` —— 加 processStream
- `interfaces/http/controller/AgentController` —— SSE 改为真流式
- `service/tools/ExecCommandTool` —— sandbox import 切到 domain.sandbox
- `domain/promptctx/source/ConstraintsSource` —— sandbox import 切到 domain.sandbox
- `infrastructure/eventbus/KafkaEventBus` —— 改为依赖 KafkaConnector
- `REFACTOR.md`（本文）

**删除（10 个）**：`service/sandbox/*.java` 全部 + 目录

### I. 与 Go 项目对齐情况
| Go 模块 | Java 当前状态 |
|---|---|
| `application/chat/{mode_react, mode_tool, planner, context_builder, memory_writer, ...}` | 已对齐 |
| `infrastructure/persistence/{chathistory, preference, longterm, snapshot, ragchunk}` | 已对齐（5 Repository） |
| `infrastructure/platform/{postgres, milvus, es, kafka, neo4j}` | 已对齐（5 Connector） |
| `infrastructure/sandbox/{docker, local, mock, factory}` | 已对齐 |
| `application/chat/process.go` SSE 流式 | 已对齐（StreamEvent + processStream） |
| `application/chat/memory_writer.go` LLM 分类 | 已对齐（identity / preference / tool_failure / policy / general 5 类） |
| `domain/memory/longterm.RecallByFilter` 类别过滤 | 已对齐（LongTermFilter 4 维度） |

### J. 后续建议（非阻塞）
- `RAG 检索（Milvus/ES）`仍留在 `InfrastructureService` 门面里，未拆出独立的 `RagSearchService`。这是有意为之 —— 避免一次性改动太大；若后续需要按子领域细分可再抽。
- LLM 流式 token 推送（`StreamEvent.token`）需要 `LlmService` 提供 streaming chat API，当前 LLM 调用是同步的，本次只到 step 粒度。
- `ChatApplicationService` 仍以 `UnifiedAgentService` 为内核委托。后续可把 tools / 4 类记忆 / KG / Sandbox 的引用拆为独立 `ChatRuntime` Bean，让 application 层完全脱钩。

