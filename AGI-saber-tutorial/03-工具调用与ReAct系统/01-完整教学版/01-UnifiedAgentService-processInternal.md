# 01-UnifiedAgentService.processInternal

## 1. 这个方法解决什么问题

`processInternal` 是一轮用户请求进入工具系统的**唯一入口**。它不负责具体执行工具，但决定了一轮请求是否走工具、走单工具还是 ReAct、用哪些工具、以及工具执行完后怎么收尾。

换句话说，它解决的是**编排问题**：

```text
把记忆准备 → 模式决策 → 工具调用 → 回答生成 → 记忆回收
这条流水线串起来。
```

没有 `processInternal`，后面所有方法（`decideMode`、`decide`、`fill`、`execute.apply`、`planGraph`、`GraphRuntime.execute`）都不会被触发。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：UnifiedAgentService.java:264-355
 */
private ChatResponse processInternal(String query, ChatRequest req, Consumer<StreamEvent> onEvent) {
    // ----- 阶段 1：初始化 + 记忆写入 -----
    cancelled.set(false);                                     // ① 重置取消标志
    ChatResponse resp = new ChatResponse();                   // ② 创建响应对象
    resp.setQuery(query);
    resp.setMode("chat");                                     // ③ 默认模式是 chat

    onEvent.accept(StreamEvent.start(query));                 // ④ 通知前端"开始了"

    stm.add("user", query);                                   // ⑤ 用户消息写入短期记忆
    infra.saveChatHistory("user", query);                     // ⑥ 持久化聊天记录

    runAsyncPreferenceExtraction(query);                      // ⑦ 异步：LLM 抽取偏好

    String[] extracted = pref.extractAndSave(query);          // ⑧ 同步：规则抽取偏好
    if (extracted != null) {
        resp.setExtractedInfo("已记住：" + extracted[0] + " = " + extracted[1]);
    }

    // ----- 阶段 2：构造记忆上下文 -----
    String memPrefix = buildMemorySystemPrefixWithCtx(query); // ⑨ 偏好 + 向量召回 组成前缀
    List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query); // ⑩ 短期记忆转 LLM 消息

    if (cancelled.get()) {                                    // ⑪ 开始前检查是否已取消
        resp.setInterrupted(true);
        resp.setAnswer("[已中断] 请求在开始前被取消");
        return resp;
    }

    // ----- 阶段 3：模式决策 -----
    String mode = ChatRouter.decideMode(                      // ⑫ 路由：chat / tool / rag / react
        query, req.isExplicit(), req.isUseRag(),
        req.getSelectedTools(), rag.isLoaded());

    Map<String, Tool> toolset = tools;                        // ⑬ 默认：全部工具可用
    if (req.isExplicit() && req.getSelectedTools() != null
            && !req.getSelectedTools().isEmpty()) {
        Map<String, Tool> filtered = filterTools(req.getSelectedTools()); // ⑭ 裁剪工具集
        if (!filtered.isEmpty()) {
            toolset = filtered;                               // ⑮ 用裁剪后的
        } else {
            mode = "chat";                                    // ⑯ 工具名全不存在 → 降级 chat
        }
    }

    resp.setMode(mode);                                       // ⑰ 写入响应
    onEvent.accept(StreamEvent.mode(mode));                   // ⑱ 通知前端模式

    // ----- 阶段 4：执行 -----
    switch (mode) {                                           // ⑲ 按模式分发
        case "react" ->
            reactLoop.runStream(resp, query, toolset, memPrefix, histMsgs, cancelled, onEvent);
        case "tool" ->
            toolHandler.run(resp, query, toolset, memPrefix, histMsgs);
        case "rag" -> {
            RagService.QueryResult qr = rag.query(query);
            resp.setAnswer(qr.answer);
            resp.setSearchResults(toSearchResults(qr.results));
            onEvent.accept(StreamEvent.ragResult(resp.getSearchResults()));
        }
        default -> {
            String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                    "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
            resp.setAnswer(llm.chat(sp, histMsgs));
        }
    }

    // ----- 阶段 5：收尾 -----
    if (cancelled.get()) resp.setInterrupted(true);           // ⑳ 标记中断

    stm.add("assistant", resp.getAnswer());                   // ㉑ 助手回答写入短期记忆
    infra.saveChatHistory("assistant", resp.getAnswer());     // ㉒ 持久化助手回答

    memoryWriter.writeAfterReply(query, resp.getAnswer());    // ㉓ 异步：LLM 分类记忆写入

    new Thread(() -> {                                        // ㉔ 异步：长期记忆合并
        if (graphMem != null && graphMem.needConsolidation()) {
            LongTermMemory.ConsolidationResult result = graphMem.graphAwareConsolidate();
            syncConsolidationToDB(result);
        } else if (ltm.needConsolidation()) {
            LongTermMemory.ConsolidationResult result = ltm.consolidate();
            syncConsolidationToDB(result);
        }
    }).start();

    // ----- 阶段 6：返回 -----
    resp.setShortTermCount(stm.size());                       // ㉕ 填充统计字段
    resp.setLongTermCount(ltm.size());
    resp.setPreferences(pref.getData());

    onEvent.accept(StreamEvent.done(resp));                   // ㉖ 通知前端完成
    return resp;
}
```

### 2.1 逐行解释

下面按 6 个阶段逐行解释每行代码解决了什么问题、为什么放在这个位置、和后续章节的衔接。

#### 方法签名

```java
private ChatResponse processInternal(String query, ChatRequest req, Consumer<StreamEvent> onEvent) {
```

- `private` — 不对外暴露。外部通过 `process()`、`processWithOptions()`、`processStream()` 三个 public 方法进入，它们最终都调到 `processInternal`
- `query` — 用户原始输入，比如 `"上海天气怎么样？"`，后面几乎每个方法都用到它
- `req` — 前端传来的请求配置，携带 `isExplicit()`、`isUseRag()`、`getSelectedTools()` 三个关键决策字段
- `onEvent` — SSE 事件回调，从 `StreamEvent.start` 推到 `StreamEvent.done`，让前端实时看到"请求在做什么"
- 返回 `ChatResponse` — 包含最终回答、模式、工具调用记录、记忆统计等

---

#### 阶段 1：初始化 + 记忆写入

**① `cancelled.set(false);`**

把取消标志重置为 `false`。`cancelled` 是 `AtomicBoolean`，多线程安全。如果上一轮请求被调了 `cancel()`，这里必须清零，否则 `cancel()` 调过一次就永远卡死，所有后续请求都走不动。

**② `ChatResponse resp = new ChatResponse();`**
**② `resp.setQuery(query);`**

创建响应对象，把用户问题原样存进去。`ChatResponse` 的字段会在整个方法的不同阶段逐步填充——`mode` 在阶段 3 写入，`answer` 在阶段 4 写入，统计字段在阶段 6 写入。

**③ `resp.setMode("chat");`**

把 `mode` 设为 `"chat"` 作为**占位默认值**。这个值只有两种命运：
- 正常情况：阶段 3 的 `decideMode` 会覆盖它
- 异常情况：如果阶段 2 末尾的取消检查命中，直接带着 `"chat"` 返回一个中断响应

**④ `onEvent.accept(StreamEvent.start(query));`**

通知前端"请求已开始处理"。SSE 模式下前端立即收到 `start` 事件，可以开始显示 loading 动画。这是 ㉖ 的对应——首尾呼应，形成完整的事件生命周期。

**⑤ `stm.add("user", query);`**

把用户消息写入短期记忆（`ShortTermMemory`，内存结构）。这是个副作用操作——不产生返回值，但改变了 `stm` 的内部状态。后续 `ChatHistoryAdapter.buildHistory(stm, query)` 在阶段 2 会把这条消息转成 LLM 认识的 `{"role":"user","content":"..."}`。

**⑥ `infra.saveChatHistory("user", query);`**

持久化到数据库。和 ⑤ 的区别：⑤ 是内存（快，进程重启丢失），⑥ 是磁盘（慢，重启后能恢复）。两边同时写，保证重启后 `restoreFromDB` 能还原对话历史。

**⑦ `runAsyncPreferenceExtraction(query);`**

启动一个**后台线程**调 LLM 从 query 抽取偏好（比如用户说"我住在徐汇区" → LLM 抽出 `{"区域":"徐汇"}`）。两点关键设计：
1. **异步**——不阻塞主流程。这轮请求用不上刚抽出的结果，下次请求才生效
2. **与 ⑧ 互补**——⑦ 走 LLM（慢但灵活），⑧ 走规则（快但死板），两者覆盖不同场景

**⑧ `String[] extracted = pref.extractAndSave(query);`**
**⑧ `if (extracted != null) { resp.setExtractedInfo(...); }`**

**同步**规则抽取。和 ⑦ 不同，这条立刻生效。比如用户说"我叫张三"，规则匹配 `"我叫XX"` 正则，抽出 `["姓名","张三"]`。非 null 表示抽到了新偏好，写入 `resp.extractedInfo` 告知前端"已记住：姓名 = 张三"。

---

#### 阶段 2：构造记忆上下文

**⑨ `String memPrefix = buildMemorySystemPrefixWithCtx(query);`**

构造 LLM 的"记忆前缀"，包含两部分：
1. **用户偏好**：`pref.buildContext()` 产出 `"用户偏好 城市：上海\n爱好：足球\n"`
2. **向量召回的相关记忆**：用 query 的 embedding 去长期记忆里搜 top-K 相似条目，回忆出用户之前聊过的相关内容

这个字符串最终拼到 LLM 的 system prompt 前面。注意它**不是给工具用的**——工具参数补全用的是 `pref` 对象直接传给 `PreferenceFiller.fill`。`memPrefix` 只影响 LLM 的回答风格。

**⑩ `List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query);`**

把短期记忆转成 LLM 的消息格式 `List<Map<String,String>>`。每一项是 `{"role":"user","content":"..."}` 或 `{"role":"assistant","content":"..."}`。这就是对话历史上下文——LLM 靠它知道"刚才聊了什么"。

**⑪ `if (cancelled.get()) { resp.setInterrupted(true); resp.setAnswer("[已中断]..."); return resp; }`**

在所有重操作（模式决策、LLM 调用、工具执行）开始前的**守卫检查**。如果用户在记忆准备阶段点了取消：
- 标记 `interrupted=true`
- 设置一个占位回答
- **直接 return**，不往下走

这个位置很关键：放在 `memPrefix` 和 `histMsgs` 构造之后、`decideMode` 之前。因为构造记忆已经做了（轻量操作），但还没进入可能耗时的 `decideMode` 和 LLM 调用。

---

#### 阶段 3：模式决策

**⑫ `String mode = ChatRouter.decideMode(query, req.isExplicit(), req.isUseRag(), req.getSelectedTools(), rag.isLoaded());`**

整个方法的**核心决策点**。调 `ChatRouter.decideMode`，返回四种可能：
- `"chat"` → 纯对话，LLM 直接回答
- `"tool"` → 单工具模式（详见第 12–16 章）
- `"rag"` → 知识库检索
- `"react"` → 多步工具编排（详见第 17–31 章）

五个参数的职责：
| 参数 | 作用 |
|---|---|
| `query` | 从中检测关键词："天气"→tool，"查…并…"→react |
| `req.isExplicit()` | 前端是否显式指定了模式（跳过关键词检测） |
| `req.isUseRag()` | 前端是否点了"使用知识库" |
| `req.getSelectedTools()` | 前端勾选的工具名 |
| `rag.isLoaded()` | 知识库是否有文档（空库不能走 rag） |

详见第 2–5 章。

**⑬ `Map<String, Tool> toolset = tools;`**

默认工具集 = 全量工具。`tools` 是在 `init()` 里组装的 `ConcurrentHashMap<String, Tool>`，包含 `get_time`、`get_weather`、`search_web`、`rag_search`（可能还有 `exec_command`）。

为什么默认是全量？因为自动模式下（`explicit=false`），系统需要所有工具可用，让 `decide` 或 `planGraph` 自己选。

**⑭ `Map<String, Tool> filtered = filterTools(req.getSelectedTools());`**

`filterTools` 的逻辑很简单：遍历前端传来的工具名列表，如果 `tools` 里有这个 key，就放进结果 Map。实现：

```java
private Map<String, Tool> filterTools(List<String> names) {
    Map<String, Tool> result = new HashMap<>();
    for (String name : names) {
        if (tools.containsKey(name)) result.put(name, tools.get(name));
    }
    return result;
}
```

**⑮ `toolset = filtered;`**

用裁剪后的工具集替换默认全量。比如前端只勾选了 `get_weather`，`toolset` 就变成只包含一个工具的 Map。

**⑯ `mode = "chat";`**

如果前端传的工具名在 `tools` 里全都不存在（拼写错误、工具被移除等），`filtered` 为空 → **降级到 chat**。这是一种防御性设计：宁可什么都不做，也不要用错误的工具集执行。

**⑰ `resp.setMode(mode);`**

把最终决定的模式写入响应对象。此时 `resp.mode` 从默认的 `"chat"` 变成真正要走的值。

**⑱ `onEvent.accept(StreamEvent.mode(mode));`**

通知前端"模式已决定"。前端收到后可以切换 UI：显示"正在调用工具…"或"正在检索知识库…"或直接显示 loading。

---

#### 阶段 4：执行分发

**⑲ `switch (mode) { ... }`**

按模式分发到不同的执行路径。这是 `processInternal` 编排职责的集中体现——它不执行，只分发。

**`case "react"`**：
```java
reactLoop.runStream(resp, query, toolset, memPrefix, histMsgs, cancelled, onEvent);
```
进入 ReAct 多步循环。传了 7 个参数：
- `cancelled` — 支持中途取消
- `onEvent` — 支持流式推送中间结果
- 内部链：`Planner.planGraph` → `TaskGraph` → `GraphRuntime.execute` → `ChatGenerator.generate`
- 详见第 17–31 章

**`case "tool"`**：
```java
toolHandler.run(resp, query, toolset, memPrefix, histMsgs);
```
进入单工具模式。只传 5 个参数，**不传** `cancelled` 和 `onEvent`——单工具模式不做流式推送和取消检查（执行时间短，一个工具调用通常几百毫秒）。内部链：`decide` → 取 Tool → `fill` → `execute.apply` → 内联 `llm.chat`。详见第 12–16 章。

**`case "rag"`**：
```java
RagService.QueryResult qr = rag.query(query);
resp.setAnswer(qr.answer);
resp.setSearchResults(toSearchResults(qr.results));
onEvent.accept(StreamEvent.ragResult(resp.getSearchResults()));
```
直接调 `rag.query()` 检索知识库。回答直接设置为检索结果——不做额外的 LLM 总结，因为 RAG 内部的 `generateFn`（在 `init()` 里设置的）已经调了 LLM。同时把检索到的文档列表写入 `searchResults`，推送给前端展示引用来源。

**`default`（即 `"chat"`）**：
```java
String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
        "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
resp.setAnswer(llm.chat(sp, histMsgs));
```
纯对话。`buildSystemPrompt` 把 `memPrefix`（偏好+相关记忆）和角色设定拼成一个完整的 system prompt，然后调 `llm.chat` 直接生成回答。不涉及任何工具或知识库。

---

#### 阶段 5：收尾

**⑳ `if (cancelled.get()) resp.setInterrupted(true);`**

执行完成后再次检查取消标志。为什么在阶段 4 **之后**检查？因为用户可能在工具执行过程中点了取消。此时 `resp.answer` 可能已经有部分结果（如 ReAct 中途已完成的部分节点结果），标记 `interrupted=true` 让调用方知道"这不是完整回答"。

**㉑ `stm.add("assistant", resp.getAnswer());`**

把助手的最终回答写入短期记忆。无论走的是哪个模式分支，最终回答都在 `resp.answer` 里。这条写完后，下轮请求的 `histMsgs` 就能看到"助手刚才说了什么"。

**㉒ `infra.saveChatHistory("assistant", resp.getAnswer());`**

持久化助手回答到数据库。和 ⑥ 对应——用户消息和助手回答都写了内存+数据库，保证对话历史完整。

**㉓ `memoryWriter.writeAfterReply(query, resp.getAnswer());`**

**异步**调用 LLM 对本轮对话内容进行分类，写入长期记忆。比如这轮聊了天气，LLM 可能把"用户在上海"分类到偏好槽位，把"今天小雨"分类到事实槽位。这和 ⑦ 的 `runAsyncPreferenceExtraction` 互补——⑦ 是**回复前**从用户消息抽取，㉓ 是**回复后**从整轮对话抽取。

**㉔ `new Thread(() -> { ... }).start();`**

启动后台线程做长期记忆合并。两个分支：
- 有知识图谱（`graphMem != null`）→ `graphAwareConsolidate()`：考虑实体关系的图感知去重合并
- 无知识图谱 → `ltm.consolidate()`：纯向量相似度去重合并

`needConsolidation()` 检查记忆条数是否超过阈值。合并结果（删除去重条目、更新合并后的条目）通过 `syncConsolidationToDB` 写回数据库。

这是**纯异步操作**——不阻塞 `return resp`。用户不需要等合并完成就能看到回答。

---

#### 阶段 6：返回

**㉕ `resp.setShortTermCount(stm.size());`**
**㉕ `resp.setLongTermCount(ltm.size());`**
**㉕ `resp.setPreferences(pref.getData());`**

填充三个统计字段。注意这些数值是**本轮处理完成后**的快照：
- `shortTermCount` — 包含刚写入的用户消息+助手回答
- `longTermCount` — 包含刚才 `memoryWriter` 可能新增的条目（异步，可能还未写入）
- `preferences` — 包含 ⑦⑧ 可能新抽取的偏好

**㉖ `onEvent.accept(StreamEvent.done(resp));`**

最后一次 SSE 推送。附带完整的 `ChatResponse`（包含 answer、mode、toolCall、searchResults、统计字段）。前端收到后结束 loading 状态，渲染最终回答。和 ④ 的 `StreamEvent.start` 首尾呼应——`start → mode → [ragResult] → done` 形成完整的事件生命周期。

```java
return resp;
```
返回给调用方（`processStream` → controller → HTTP response）。

---

### 2.2 六阶段职责总览

| 阶段 | 行数 | 核心职责 | 关键方法调用 |
|---|---|---|---|
| 1 初始化+记忆写入 | 265–282 | 创建响应对象、写入用户消息、抽取偏好 | `stm.add`, `pref.extractAndSave`, `runAsyncPreferenceExtraction` |
| 2 构造记忆上下文 | 284–291 | 构建 LLM 的系统提示和对话历史 | `buildMemorySystemPrefixWithCtx`, `buildHistory` |
| 3 模式决策 | 294–307 | 决定走 chat/tool/rag/react，裁剪工具集 | `decideMode`, `filterTools` |
| 4 执行分发 | 309–323 | 按模式分发到不同 handler | `reactLoop.runStream`, `toolHandler.run`, `rag.query`, `llm.chat` |
| 5 收尾 | 325–346 | 写回答到记忆、异步记忆合并、事件发布 | `stm.add`, `memoryWriter.writeAfterReply`, `consolidate` |
| 6 返回 | 349–354 | 填充统计字段、推送完成事件、返回 | `onEvent.accept(done)`, `return resp` |

## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `query` | `String` | 用户原始输入，比如 `"上海天气怎么样？"` |
| `req` | `ChatRequest` | 请求配置对象，携带三个关键字段 |
| `req.isExplicit()` | `boolean` | 前端是否显式触发了工具/ReAct 模式 |
| `req.isUseRag()` | `boolean` | 前端是否要求使用私人知识库 |
| `req.getSelectedTools()` | `List<String>` | 前端勾选的工具名列表，如 `["get_weather"]` |
| `onEvent` | `Consumer<StreamEvent>` | SSE 事件回调，用于流式推送给前端 |

**`req` 的三个字段决定了 mode 的走向**，这是第 2–6 章要详细展开的内容。

**`onEvent` 贯穿全流程**，从 `StreamEvent.start` 到 `StreamEvent.mode` 到 `StreamEvent.done`，确保前端能实时看到"请求在做什么"。

## 4. 返回值/副作用解释

**返回值**：`ChatResponse`，包含：

| 字段 | 含义 |
|---|---|
| `query` | 原样返回用户问题 |
| `answer` | 最终回答——要么是 LLM 直接生成，要么是工具结果经 LLM 总结 |
| `mode` | 实际走的模式：`chat` / `tool` / `rag` / `react` |
| `toolCall` | 单工具模式下，保存完整的 `ToolCallResult` |
| `searchResults` | RAG 模式下，保存知识库检索结果 |
| `interrupted` | 是否被中途取消 |
| `shortTermCount` / `longTermCount` | 记忆条数统计 |
| `preferences` | 当前偏好数据 |

**副作用（比返回值更重要）**：

1. `stm.add(...)` — 用户消息和助手回答写入短期记忆，影响后续对话的 `histMsgs`
2. `pref.extractAndSave(query)` — 可能从 query 中抽取出新偏好（如"我在上海"）
3. `memoryWriter.writeAfterReply(...)` — 异步把本轮对话内容分类写入长期记忆
4. 长期记忆合并线程 — 可能触发去重、合并、过期清理

核心理解：**`processInternal` 是副作用密集的方法，返回值只是冰山一角**。

## 5. 这一步在完整链路中的位置

```text
用户 query 到达
  ↓
processInternal
  ├── 阶段 1：记忆写入（写 stm，抽取偏好）
  ├── 阶段 2：构造上下文（memPrefix, histMsgs）
  ├── 阶段 3：模式决策（decideMode）         ← 第 2–5 章
  ├── 阶段 3：工具集裁剪（filterTools）      ← 第 6 章
  ├── 阶段 4：执行分发（switch mode）
  │   ├── react → ReActLoop.runStream        ← 第 17–31 章
  │   ├── tool  → ToolModeHandler.run        ← 第 12–16 章
  │   ├── rag   → RagService.query
  │   └── chat  → llm.chat
  ├── 阶段 5：记忆回收（写 stm，异步记忆）
  └── 返回 ChatResponse
```

**关键时间线**：

- `decideMode` 在 `memPrefix` 构造**之后**调用——因为模式决策需要知道 RAG 是否已加载，不需要 memPrefix
- `filterTools` 在 `decideMode` **之后**调用——先决定走什么模式，再裁剪工具
- 工具执行在记忆写入**之后**、回答写入**之前**——工具结果是回答的材料

## 6. 用"上海天气怎么样？"跑一遍

用户输入：

```text
query = "上海天气怎么样？"
```

`processInternal` 的执行轨迹：

```text
① cancelled = false
② resp.mode = "chat"（默认值，马上会被覆盖）
③ stm.add("user", "上海天气怎么样？")
④ 异步抽取偏好 + 同步规则抽取（这句没有明显的偏好格式，跳过）
⑤ memPrefix = "用户默认城市：上海"（假设长期偏好里存了）
⑥ histMsgs = [{"role":"user","content":"上海天气怎么样？"}]

⑦ decideMode("上海天气怎么样？", explicit=false, useRag=false, selectedTools=[], ragLoaded=true)
   → needReAct? "天气"命中 1 个，搜索未命中，count=1 < 2 → false
   → needTool? "天气"命中 → true
   → 返回 "tool"

⑧ explicit=false，不走 filterTools 分支
⑨ toolset = tools（全部工具可用，包含 get_time, get_weather, search_web, rag_search）

⑩ switch("tool") → toolHandler.run(resp, "上海天气怎么样？", toolset, memPrefix, histMsgs)
    → ToolModeHandler 内部（第 12 章详讲）：
      decide → get_weather, params={city=上海}
      PreferenceFiller.fill → city 已有值"上海"，不覆盖
      execute.apply → "上海：小雨 20°C"
      llm.chat → "上海目前小雨，约 20°C，出门建议带伞。"

⑪ resp.answer = "上海目前小雨，约 20°C，出门建议带伞。"
⑫ stm.add("assistant", resp.answer)
⑬ 异步写长期记忆 + 记忆合并
⑭ 返回 resp
```

注意第 ⑨ 步：`toolset` 是全量工具，没有裁剪。如果前端显式传了 `selectedTools=["get_weather"]`，`toolset` 才会变成只包含 `get_weather` 的子集。

## 7. 常见误解

**误解一："processInternal 自己选择工具"**

不对。它只负责分发到 mode handler。选工具是 `ToolModeHandler.run` → `ToolService.decide` 的职责。

**误解二："mode 默认是 chat，如果出了问题会走 chat"**

`resp.setMode("chat")` 是一开始的默认值，但 `decideMode` 会覆盖它。只有一种情况会真正降级到 chat：`explicit=true` 且 `selectedTools` 经 `filterTools` 过滤后为空——此时 mode 被强设为 `"chat"`（第 ⑯ 步）。

**误解三："memPrefix 是给工具用的"**

不全对。`memPrefix` 主要给 LLM 的系统提示用——让 LLM 知道用户偏好，生成更个性化的回答。但它也间接影响工具：`PreferenceMemory`（`pref` 对象）会传给 `PreferenceFiller.fill`，用来补工具参数。

**误解四："filterTools 在所有模式下都生效"**

不对。`filterTools` 只在 `explicit=true` 且 `selectedTools` 非空时才调用。普通自动模式下，`toolset = tools`（全量工具库）。

**误解五："longTermCount 和 shortTermCount 是实时计算的"**

它们是 `processInternal` 末尾才写入的统计值，反映的是本轮请求**处理完**后的记忆条数，不是请求开始前的状态。
