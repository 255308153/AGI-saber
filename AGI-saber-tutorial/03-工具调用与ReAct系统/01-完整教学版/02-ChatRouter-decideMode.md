# 02-ChatRouter-decideMode

## 1. 这个方法解决什么问题

用户一句话进来，系统要决定走四条路中的哪一条：

```text
chat  — 不需要工具，LLM 直接回答
tool  — 需要一个工具（天气、时间、搜索）
rag   — 从私人知识库检索
react — 需要多个工具协作（查天气 + 搜索建议）
```

`decideMode` 就是做这个**四分决策**的方法。它不选具体工具，不执行工具，只返回一个模式字符串。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ChatRouter.java:50-63
 *
 * 参数：
 *   query         - 用户原始输入
 *   explicit      - 前端是否显式触发（用户点了按钮）
 *   useRag        - 前端是否请求使用知识库
 *   selectedTools - 前端勾选的工具名列表
 *   ragLoaded     - 知识库是否已加载文档
 *
 * 返回："react" | "tool" | "rag" | "chat"
 */
public static String decideMode(String query, boolean explicit, boolean useRag,
                                List<String> selectedTools, boolean ragLoaded) {
    // ===== 显式模式：信任前端 =====
    if (explicit) {
        if (selectedTools != null && !selectedTools.isEmpty()) {
            return "react";                       // ① 前端勾了工具 → 直接走 ReAct
        }
        if (useRag && ragLoaded) return "rag";    // ② 前端点了"搜索知识库"
        return "chat";                            // ③ 显式但什么都没选 → chat
    }

    // ===== 自动模式：按规则逐级判断 =====
    if (needReAct(query)) return "react";         // ④ 多步骤任务
    if (needTool(query)) return "tool";           // ⑤ 单工具触发
    if (needRAG(query, ragLoaded)) return "rag";  // ⑥ RAG 兜底
    return "chat";                                // ⑦ 都不触发 → 纯聊天
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 前端勾了工具 → 直接走 ReAct**

前端勾了工具 → 直接走 ReAct。显式选择工具时，系统不再猜“单工具还是多工具”，统一交给 ReAct，让 Planner 在选中的工具集合里规划节点。

**② 前端点了"搜索知识库"**

前端点了"搜索知识库"。只有 `useRag=true` 且 `ragLoaded=true` 时才返回 `rag`；如果知识库没加载，就不能走 RAG，只能继续 fallback 到 chat。

**③ 显式但什么都没选 → chat**

显式但什么都没选 → chat。用户打开了显式模式，但没有选工具也没有可用 RAG，系统就不做自动关键词判断，直接按普通聊天处理。

**④ 多步骤任务**

多步骤任务。`needReAct(query)` 返回 true 时，说明 query 命中了两个以上语义维度，单工具模式不够，所以直接返回 `react`。

**⑤ 单工具触发**

单工具触发。`needTool(query)` 返回 true 时，只说明需要工具能力，具体调用哪个工具要等 `ToolService.decide` 再判断。

**⑥ RAG 兜底**

RAG 兜底。只有前面的 ReAct 和 Tool 都不触发，且知识库已加载时，才进入 RAG，避免实时工具请求被离线知识库抢走。

**⑦ 都不触发 → 纯聊天**

都不触发 → 纯聊天。说明这条 query 不需要工具、不需要任务图、不需要知识库，后面会走 `llm.chat(sp, histMsgs)`。


## 3. 参数逐个解释

| 参数 | 类型 | 谁传进来的 | 含义 |
|---|---|---|---|
| `query` | `String` | `processInternal` 直接传 | 用户原始输入 |
| `explicit` | `boolean` | `ChatRequest.isExplicit()` | 前端是否点击了工具/知识库按钮 |
| `useRag` | `boolean` | `ChatRequest.isUseRag()` | 前端是否勾选了"搜索知识库" |
| `selectedTools` | `List<String>` | `ChatRequest.getSelectedTools()` | 前端勾选的工具名，如 `["get_weather"]` |
| `ragLoaded` | `boolean` | `rag.isLoaded()` | 知识库是否有已上传文档 |

**`explicit` 是最高优先级的参数**。当它等于 `true`，`query` 的内容完全不被检查——系统直接按前端的选择走。

**`ragLoaded` 来自 `RagService.isLoaded()`**。它检查的是向量库中是否有已索引的文档。如果用户从未上传文档，即使前端勾了"搜索知识库"，`ragLoaded=false`，显式 RAG 也不会生效（会 fallback 到 chat）。

## 4. 返回值/副作用解释

**返回值**：四个字符串之一：

| 返回值 | 含义 | 后续执行 |
|---|---|---|
| `"react"` | 多步推理 | `ReActLoop.runStream` |
| `"tool"` | 单工具调用 | `ToolModeHandler.run` |
| `"rag"` | 知识库检索 | `rag.query(query)` |
| `"chat"` | 纯 LLM 对话 | `llm.chat(sp, histMsgs)` |

**副作用**：无。`decideMode` 是纯函数——所有参数都是传入的，不修改任何外部状态。

## 5. 这一步在完整链路中的位置

```text
processInternal
  ├── 构造 memPrefix, histMsgs
  ├── decideMode ← 你在这里
  ├── filterTools（如果 explicit）
  ├── switch(mode) 执行
  └── 记忆回收
```

`decideMode` 在 `memPrefix` 构造之后、工具集裁剪之前被调用。但注意：**`decideMode` 本身不依赖 `memPrefix` 或 `histMsgs`**——它只看 `query` 和请求参数。之所以放在这个位置，是因为 `rag.isLoaded()` 需要 RAG 服务已初始化。

**`decideMode` 是本章主题。它的两个关键子方法 `needTool`（第 3 章）和 `needReAct`（第 4 章）、`needRAG`（第 5 章）各有独立章节。**

## 6. 用"上海天气怎么样？"跑一遍

输入：

```text
query = "上海天气怎么样？"
explicit = false
useRag = false
selectedTools = []
ragLoaded = true
```

执行过程：

```text
① explicit = false → 跳过显式分支

② 自动模式：
   needReAct("上海天气怎么样？")
   → "天气" 命中 → count=1
   → "查"/"搜索"/"总结"/"汇总" 未命中
   → count=1 < 2 → 返回 false

③ needTool("上海天气怎么样？")
   → "天气" 命中 → 返回 true

④ 进入 if (needTool(query)) → return "tool"
```

最终 `decideMode` 返回 `"tool"`。

对比另一个例子——"查一下上海天气，并搜索小雨出门建议"：

```text
needReAct("查一下上海天气，并搜索小雨出门建议")
→ "天气" 命中 → count=1
→ "查"+"搜索" 命中 → count=2
→ count=2 >= 2 → 返回 true

needTool 不会被执行，因为 needReAct 已经返回 true
```

最终返回 `"react"`。

## 7. 常见误解

**误解一："explicit=true 时 selectedTools 为空，会走 needTool/needReAct 判断"**

不会。显式分支是完整的 if-else——`explicit=true` 时直接返回，根本不会进入下面的自动规则。所以如果用户点了工具按钮但没选任何工具，结果是 `chat`，不是自动判断。

**误解二："needReAct 和 needTool 是互斥的"**

它们不是互斥的——`needReAct` 判断为 `true` 的 query，`needTool` 通常也会判断为 `true`（因为 `needReAct` 的条件是 `needTool` 条件的超集）。关键是 if-else 的顺序：**`needReAct` 先判断**，它返回 `true` 就拦截了，不会走到 `needTool`。

**误解三："useRag=true 就一定走 RAG"**

显式模式下，`useRag=true` 还要 `ragLoaded=true` 才走 RAG。自动模式下，RAG 的触发条件更严格（第 5 章详讲）：`ragLoaded && !needTool(query) && !needReAct(query)`。

**误解四："decideMode 用到了 LLM"**

没有。`decideMode` 及其子方法 `needTool`、`needReAct`、`needRAG` 全部是关键词匹配，不调用任何 LLM。这是刻意设计——路由决策要快，不能为"判断要不要用 LLM"而先调用一次 LLM。
