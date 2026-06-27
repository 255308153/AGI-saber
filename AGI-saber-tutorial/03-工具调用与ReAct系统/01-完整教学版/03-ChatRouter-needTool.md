# 03-ChatRouter-needTool

## 1. 这个方法解决什么问题

回答一个最基础的问题：**这句话需要调用工具吗？**

`needTool` 不选具体工具，不判断用哪个工具，只回答"需要"还是"不需要"。它是一道门槛——过了这道门槛，query 才会进入单工具链路（`ToolModeHandler.run`）。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ChatRouter.java:21-26
 *
 * 参数：query - 用户原始输入
 * 返回：true = 需要单工具，false = 不需要
 */
public static boolean needTool(String query) {
    String q = query == null ? "" : query.toLowerCase(Locale.ROOT);  // ① 转小写，统一匹配
    return q.contains("几点") || q.contains("时间")                    // ② 时间类
            || q.contains("天气") || q.contains("查")                  // ③ 天气 + 通用查询
            || q.contains("搜索") || q.contains("是什么");              // ④ 搜索 + 定义类
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 转小写，统一匹配**

转小写，统一匹配。这一步做数据形态转换，让下游方法拿到它期望的结构。

**② 时间类**

时间类。命中这类关键词说明 query 可能需要时间工具；这里只做意图计数或布尔判断，不执行工具。

**③ 天气 + 通用查询**

天气 + 通用查询。命中天气关键词说明 query 可能需要天气工具；具体城市参数会在 ToolService.decide 或 PreferenceFiller.fill 中处理。

**④ 搜索 + 定义类**

搜索 + 定义类。命中 `查/搜索/是什么` 只说明可能需要搜索工具；具体是否能用 `search_web`，还要看后面的工具集里有没有这个 key。


## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `query` | `String` | 用户原始输入 |

就一个参数。`query` 可以为 `null`——方法第一行做了防御：`query == null ? ""`。

## 4. 返回值/副作用解释

**返回值**：`boolean`

**副作用**：无。纯函数。

**六个关键词分成三组**：

| 组 | 关键词 | 典型 query | 触发工具 |
|---|---|---|---|
| 时间 | `几点`、`时间` | "现在几点了？" | `get_time` |
| 天气 | `天气` | "上海天气怎么样？" | `get_weather` |
| 搜索/查询 | `查`、`搜索`、`是什么` | "查一下 Go 语言"  | `search_web` |

**注意 `查` 的歧义**。`"查一下 Go 语言"` 触发工具，但 `"调查问卷怎么做"` 也会触发——`查` 字太常见。这是关键词匹配的固有局限：宁可多触发（走到 `decide` 再降级），不可漏触发。

## 5. 这一步在完整链路中的位置

```text
decideMode(query, ...)
  ├── explicit? → 跳过自动判断
  ├── needReAct(query)? → react      ← 第 4 章
  ├── needTool(query)? → tool         ← 你在这里
  ├── needRAG(query)? → rag          ← 第 5 章
  └── 以上都不满足 → chat
```

**`needTool` 的优先级低于 `needReAct`**。如果同一个 query 同时满足 `needReAct` 和 `needTool`，`needReAct` 先返回 `true`，`needTool` 根本不会被调用。这是 `decideMode` 里 if-else 顺序决定的。

## 6. 用"上海天气怎么样？"跑一遍

```text
query = "上海天气怎么样？"
↓ toLowerCase
q = "上海天气怎么样？"
↓
q.contains("几点")    → false
q.contains("时间")    → false
q.contains("天气")    → true  → 短路返回 true
```

结果：`needTool` 返回 `true`。`decideMode` 进入 `return "tool"`。

再看一个不触发的例子：

```text
query = "解释一下 HashMap 原理"
↓
q.contains("几点")    → false
q.contains("时间")    → false
q.contains("天气")    → false
q.contains("查")      → false
q.contains("搜索")    → false
q.contains("是什么")  → false

→ 返回 false
→ decideMode 继续判断 needRAG → needRAG 也不满足（ragLoaded=true 但不是知识性问题）
→ 最终返回 "chat"
```

## 7. 常见误解

**误解一："needTool 会判断用哪个工具"**

不会。`needTool` 只看关键词是否命中，不区分"天气"触发 `get_weather` 还是"时间"触发 `get_time`。具体工具选择是 `ToolService.decide`（第 13 章）的职责——它根据相同的关键词分支来决定 `toolName`。

**误解二："所有包含'时间'的问题都需要工具"**

"时间"确实会触发 `needTool`，但如果 `toolset` 里没有 `get_time`，`ToolService.decide` 会走到 fallback 选第一个工具——这并不是理想行为。关键词触发只是第一道筛选，后续还有工具存在性检查。

**误解三："needTool 和 needReAct 覆盖了所有需要工具的场景"**

没有。比如 `"帮我计算 123 * 456"`——不含时间/天气/查/搜索/是什么中的任何一个。`needTool` 返回 `false`，最终走 `chat`。但这条 query 如果有一个 `calculator` 工具，应该触发工具。当前的关键词列表是**硬编码的**，只覆盖了三个工具。如果后续注册了新工具（比如 `exec_command`），需要在 `needTool` 和 `needReAct` 里加对应关键词，或者依赖 `explicit=true` 路径。
