# 05-ChatRouter-needRAG

## 1. 这个方法解决什么问题

知识库检索（RAG）和工具调用有冲突：如果 query 已经触发了工具，就不该再走 RAG——因为工具会调用外部能力拿实时数据，RAG 检索的是离线文档。

`needRAG` 解决的是**RAG 的触发条件**：只有在不需要工具和 ReAct 的前提下，才考虑走私人知识库。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ChatRouter.java:29-31
 *
 * 参数：
 *   query     - 用户原始输入
 *   ragLoaded - 知识库是否已加载文档
 *
 * 返回：true = 走 RAG，false = 不走
 */
public static boolean needRAG(String query, boolean ragLoaded) {
    return ragLoaded && !needTool(query) && !needReAct(query);  // ① 三个条件必须同时满足
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 三个条件必须同时满足**

三个条件必须同时满足。这里是保护条件，目的是避免后面的执行逻辑拿到空对象、错误工具或不满足条件的数据。


## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `query` | `String` | 用户原始输入 |
| `ragLoaded` | `boolean` | `RagService.isLoaded()` 的返回值 |

**`ragLoaded` 不是一个静态配置项**。它在 `processInternal` 调用 `decideMode` 之前就已经确认——来自 `rag.isLoaded()`，检查向量库是否有已索引的文档。如果用户从未在"私人黑洞"上传文档，`ragLoaded` 始终为 `false`。

## 4. 返回值/副作用解释

**返回值**：`boolean`

**副作用**：无。

**三个条件缺一不可**：

```text
ragLoaded = true          知识库里有文档
AND
!needTool(query) = true   不需要单工具
AND
!needReAct(query) = true  不需要多步推理
```

为什么 `!needTool(query)` 和 `!needReAct(query)` 是必要条件？因为工具查询（天气、时间、搜索）需要实时数据，RAG 文档是离线数据。如果 query 同时能被工具和 RAG 处理，优先走工具——实时数据比离线文档更准确。

## 5. 这一步在完整链路中的位置

```text
decideMode(query, ...)
  ├── explicit? → useRag && ragLoaded → "rag"
  ├── needReAct(query)? → "react"
  ├── needTool(query)? → "tool"
  ├── needRAG(query, ragLoaded)? → "rag"  ← 你在这里
  └── "chat"
```

**RAG 在自动模式里排最后**。它被设计成"兜底增强"——不需要外部工具、不需要多步推理时，如果有知识库就用知识库增强回答，没有就走纯 chat。

## 6. 用例子跑一遍

**例子 A：RAG 触发**

```text
query = "AGI-Saber 的记忆系统是怎么设计的？"
ragLoaded = true

needTool("AGI-Saber 的记忆系统...")
  → 含"是什么" → true
  → !needTool = false
  → needRAG 返回 false

实际上"AGI-Saber 的记忆系统"是一个知识性问题，更适合走 RAG。但因为 query 包含"是什么"，needTool 返回了 true。
```

这个例子暴露了关键词匹配的局限。"是什么"本来是为 `search_web` 工具设计的（"AI 应用工程师是什么"），但也会误匹配知识性问题。

**例子 B：RAG 真正触发**

```text
query = "记忆合并的策略有哪些？"
ragLoaded = true

needTool("记忆合并的策略有哪些？")
  → 无"几点/时间/天气/查/搜索/是什么" → false
needReAct("记忆合并的策略有哪些？")
  → 无命中 → count=0 → false

!needTool = true
!needReAct = true
ragLoaded = true

→ needRAG 返回 true
→ decideMode 返回 "rag"
```

**例子 C：RAG 被工具抢占**

```text
query = "搜索记忆合并的策略"
ragLoaded = true

needTool("搜索记忆合并的策略")
  → 含"搜索" → true

!needTool = false
→ needRAG 返回 false
→ decideMode 走 needTool → "tool"
→ 走到 ToolService.decide → search_web → mock 搜索结果
```

如果用户的私人知识库里有"记忆合并"的文档，这个 query 走 RAG 会更合适。但因为含了"搜索"，被导向了 `search_web` 工具。

## 7. 常见误解

**误解一："RAG 和 search_web 是用同一种方式触发的"**

完全不同。`search_web` 是一个**工具**——它通过 `needTool` → `ToolService.decide` → `search_web` 触发。RAG 是一个**模式**——它通过 `needRAG` → `rag.query()` 触发。

区别在于：
- `search_web` 搜索互联网，结果交给 LLM 总结
- RAG 搜索私人知识库，结果直接返回（或经 LLM 增强）

**误解二："只要知识库有文档，所有问题都会走 RAG"**

不会。因为 `!needTool(query) && !needReAct(query)` 的前置条件，只要 query 包含工具触发词，RAG 就被跳过。这是一种"工具优先"的设计——假设实时数据 > 离线文档。

**误解三："ragLoaded 检查的是知识库是否启用"**

检查的是**是否有文档**，不是配置项是否开启。如果配置启用了 RAG 但用户从未上传文档，`rag.isLoaded()` 返回 `false`，RAG 永远不会触发。

**误解四："needRAG 的优先级可以通过调整 decideMode 里的顺序来改变"**

是的。当前顺序是 `needReAct → needTool → needRAG → chat`。如果想让 RAG 优先于 tool，把 `needRAG` 的判断提到 `needTool` 前面即可。但这会改变行为——比如"搜索记忆合并的策略"就会走 RAG 而不是 `search_web`。当前设计选择"工具优先"是有意为之。
