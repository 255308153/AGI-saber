# 13-ToolService-decide

## 1. 这个方法解决什么问题

工具集（`ts`）里有多个工具，但一次单工具调用只需要其中一个。`decide` 从用户 query 中判断该用哪个——是 `get_time`、`get_weather` 还是 `search_web`——并生成对应的 `ToolCallResult`。

它不是用 LLM 决策的，而是**规则匹配**：看 query 里包含什么关键词。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ToolService.java:80-107
 *
 * 参数：
 *   query - 用户原始输入
 *   tools - 本轮可用工具集
 *
 * 返回：ToolCallResult（决策结果），或 null（工具集为空）
 */
public ToolCallResult decide(String query, Map<String, Tool> tools) {
    String q = query.toLowerCase();                               // ① 统一小写

    // ===== 分支 1：时间 =====
    if ((q.contains("几点") || q.contains("时间"))                  // ② 时间关键词
            && tools.containsKey("get_time")) {                   // ③ 工具存在才进入
        Map<String, Object> params = new HashMap<>();
        if (q.contains("东京")) params.put("timezone", "Asia/Tokyo"); // ④ 特殊时区
        return new ToolCallResult("get_time", params);            // ⑤ 返回决策
    }

    // ===== 分支 2：天气 =====
    if (q.contains("天气") && tools.containsKey("get_weather")) {  // ⑥ 天气关键词 + 工具存在
        Map<String, Object> params = new HashMap<>();
        for (String c : List.of("东京", "北京", "上海",               // ⑦ 遍历城市列表
                "纽约", "伦敦", "广州", "深圳")) {
            if (q.contains(c)) { params.put("city", c); break; }  // ⑧ 匹配到就写入
        }
        // 未指定城市时不填 city，留给 PreferenceFiller.fill() 从偏好记忆补充
        return new ToolCallResult("get_weather", params);         // ⑨ params 可能为 {}
    }

    // ===== 分支 3：搜索 =====
    if ((q.contains("查") || q.contains("搜索")                    // ⑩ 搜索关键词
            || q.contains("是什么"))
            && tools.containsKey("search_web")) {
        return new ToolCallResult("search_web", Map.of("query", query)); // ⑪ 整句当参数
    }

    // ===== 分支 4：fallback =====
    for (String name : tools.keySet()) {                          // ⑫ 遍历所有工具
        return new ToolCallResult(name, Map.of("query", query));  // ⑬ 选第一个
    }
    return null;                                                  // ⑭ 工具集为空
}
```
### 2.1 逐行解释

下面按编号说明 `decide` 怎么把一句自然语言变成 `ToolCallResult(toolName, params)`。注意：它不执行工具，也不产生 `toolResult`。

**① 统一小写**

把 `query` 转成小写后放进 `q`，方便匹配英文关键词或大小写混合内容。中文本身没有大小写，但这里统一处理是为了让 `"Go"`、`"AI"` 这类输入也能稳定匹配。原始 `query` 没丢，搜索分支还会把原句传给 `search_web`。

**② 时间关键词**

检查用户是否在问时间。`"几点"` 和 `"时间"` 任意一个命中，就说明 query 可能适合 `get_time`。这里只判断语义触发，不代表工具一定可用。

**③ 工具存在才进入**

继续检查本轮工具集里是否真的有 `get_time`。如果前端只选了 `get_weather`，即使 query 里有 `"几点"`，这个分支也不会进入。这样 `decide` 的结果始终受 `toolset` 约束。

**④ 特殊时区**

当前规则只识别 `"东京"` 这一个时区提示。命中后写入 `params.put("timezone", "Asia/Tokyo")`。如果用户问 `"现在几点了"` 没说地点，`params` 保持空 Map，执行工具时会用系统默认时区。

**⑤ 返回决策**

构造并返回 `new ToolCallResult("get_time", params)`。此时 `toolResult` 还是 `null`，因为这里只完成“决定调用什么”，真正执行发生在 `ToolModeHandler.run` 的 `tool.getExecute().apply(...)`。

**⑥ 天气关键词 + 工具存在**

检查 query 是否包含 `"天气"`，并且本轮工具集里有 `get_weather`。两个条件同时满足才进入天气分支。这样可以避免没有天气工具时还返回 `get_weather`。

**⑦ 遍历城市列表**

依次检查 query 是否包含这些硬编码城市：东京、北京、上海、纽约、伦敦、广州、深圳。这是规则抽参，不是地名识别模型，所以只能识别列表里写死的城市。

**⑧ 匹配到就写入**

如果 query 里出现某个城市，就把 `city` 写进 `params`，然后 `break` 停止继续找。比如 `"上海天气怎么样"` 会写入 `{city:"上海"}`。这属于用户显式给出的参数，后面的 `PreferenceFiller.fill` 不能覆盖它。

**⑨ params 可能为 {}**

返回 `ToolCallResult("get_weather", params)`。如果 query 没有命中城市，`params` 就是空 Map。这是刻意设计：不在 `decide` 里默认填 `"北京"`，而是把缺失的 `city` 留给 `PreferenceFiller.fill` 根据用户偏好补。

**⑩ 搜索关键词**

检查 query 是否包含 `"查"`、`"搜索"` 或 `"是什么"`。这些词被视为搜索类意图。这个规则比较粗糙，比如知识库问题里有 `"是什么"` 也可能被误判为搜索工具。

**⑪ 整句当参数**

搜索工具的参数是 `{query: 原始query}`，不是小写后的 `q`。这样能保留用户原句里的大小写、中文标点和专有名词。返回后，`ToolModeHandler.run` 会拿这个参数执行 `search_web`。

**⑫ 遍历所有工具**

前三个分支都没命中时，进入 fallback。这里遍历 `tools.keySet()`，准备随便选第一个工具。这不是智能决策，只是兜底。

**⑬ 选第一个**

直接返回第一个工具，并把整句 query 塞进 `{query: query}`。这个设计风险很大，因为第一个工具可能根本不接受 `query` 参数，例如 `get_time` 只看 `timezone`。文档后面要明确把它当作当前实现的缺陷。

**⑭ 工具集为空**

如果连 fallback 都没有返回，说明 `tools` 是空 Map。方法返回 `null`，调用方 `ToolModeHandler.run` 会给出 `"我无法处理这个请求。"`。


## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `query` | `String` | 用户原始输入 |
| `tools` | `Map<String, Tool>` | 本轮可用工具集（可能是全量，也可能被 `filterTools` 裁剪） |

**`tools` 可以是空 Map**。如果为空，所有分支的 `containsKey` 都不满足，走到第 ⑭ 行返回 `null`。`ToolModeHandler.run` 看到 `null` 会返回"我无法处理这个请求。"。

## 4. 返回值/副作用解释

**返回值**：`ToolCallResult` 或 `null`

**副作用**：无。

**四个分支的决策逻辑**：

| 分支 | 触发条件 | 返回的 toolName | 返回的 params | 特殊情况 |
|---|---|---|---|---|
| 时间 | query 含 "几点"/"时间" | `get_time` | `{}` 或 `{timezone: "Asia/Tokyo"}` | 只有"东京"触发时区 |
| 天气 | query 含 "天气" | `get_weather` | `{}` 或 `{city: "上海"}` | 城市列表只有 7 个 |
| 搜索 | 含 "查"/"搜索"/"是什么" | `search_web` | `{query: query}` | 整句 query 传入 |
| fallback | 前三个都不满足 | 第一个工具 | `{query: query}` | **危险，见下** |

**fallback 分支的危险**：

```text
query = "帮我计算 123 * 456"

前三个分支都不满足
→ fallback 选中第一个工具（可能是 get_time）
→ ToolCallResult("get_time", {query: "帮我计算 123 * 456"})
→ get_time.execute.apply → 返回当前时间（和 query 完全无关）
→ LLM 被要求根据"当前时间"来回答"123 * 456"
```

fallback 会导致"用完全不相关的工具处理 query"。query 内容和工具能力之间没有语义匹配。这是关键词匹配方案的固有缺陷。解决方案是：LLM-based 决策（但当前系统没有实现，fallback 依然存在）。

## 5. 这一步在完整链路中的位置

```text
ToolModeHandler.run
  ├── ① toolService.decide(query, ts) → ToolCallResult  ← 你在这里
  ├── ② ts.get(tc.getToolName()) → Tool
  ├── ③ PreferenceFiller.fill(tc, pref) → 补参
  ├── ④ tool.getExecute().apply(params) → 执行
  └── ⑤ llm.chat → 总结
```

`decide` 只负责第 ① 步，它的产出 `ToolCallResult` 包含**决策**（toolName + params），不包含**执行结果**（toolResult = null）。

## 6. 用三个 query 跑一遍

### 场景 A：时间查询

```text
query = "现在东京几点了？"
tools = {get_time, get_weather, search_web}

q = "现在东京几点了？"

分支 1：q.contains("几点") → true ✓
        tools.containsKey("get_time") → true ✓
        q.contains("东京") → true → params.put("timezone", "Asia/Tokyo")
        → return ToolCallResult("get_time", {timezone: "Asia/Tokyo"})
```

### 场景 B：天气查询（无城市）

```text
query = "今天天气怎么样？"
tools = {get_time, get_weather, search_web}

分支 1：q.contains("几点") || q.contains("时间") → "时间" 命中！true

等等——"今天天气怎么样？" 中 "天气" 和 "时间" 都不包含？不，"时间" 在 "天气" 里不存在……

仔细看：q = "今天天气怎么样？"
q.contains("几点") → false
q.contains("时间") → false
→ 分支 1 不满足

分支 2：q.contains("天气") → true
        tools.containsKey("get_weather") → true ✓
        遍历城市列表：都不匹配
        → return ToolCallResult("get_weather", {}) ← 空 params

ToolModeHandler 中 PreferenceFiller.fill 会把偏好城市补进去
```

### 场景 C：搜索

```text
query = "AI 应用工程师是什么"
tools = {get_time, get_weather, search_web}

分支 1：不满足（无时间关键词）
分支 2：不满足（无天气关键词）
分支 3：q.contains("是什么") → true
        tools.containsKey("search_web") → true ✓
        → return ToolCallResult("search_web", {query: "AI 应用工程师是什么"})
```

## 7. 常见误解

**误解一："decide 只是从 query 中提取参数，不决定工具名"**

不是。`decide` 的四个分支**同时决定**工具名和初始参数。分支名称（时间/天气/搜索）已经暗示了工具名。

**误解二："decide 产出的 params 就是最终参数"**

不是。`decide` 产出的 `params` 是**初值**——只包含从 query 文本中直接提取的参数。它可能缺失参数（如 `{}`），后续由 `PreferenceFiller.fill`（第 14 章）补充。

**误解三："decide 会校验工具参数是否完整"**

不会。比如天气分支返回 `ToolCallResult("get_weather", {})` 时，`city` 参数是空的。`decide` 不会报错说你没提供必填参数——补参在下一阶段完成。

**误解四："fallback 分支是多余的，永远不会执行"**

如果 `ts` 中有除了 `get_time`、`get_weather`、`search_web` 之外的工具（如 `exec_command`），且 query 不包含时间/天气/搜索关键词，就会进入 fallback——随机选第一个工具。这不是合理行为，但代码中确实存在。

**误解五："decide 用到了 LLM"**

没有。完全是 `String.contains` 关键词匹配。第 18 章的 `Planner.planGraph` 才会用 LLM——但那是在 ReAct 模式下。单工具模式的 decision 是纯规则。
