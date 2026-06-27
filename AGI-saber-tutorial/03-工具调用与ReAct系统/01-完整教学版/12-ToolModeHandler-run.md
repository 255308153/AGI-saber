# 12-ToolModeHandler-run

## 1. 这个方法解决什么问题

`ToolModeHandler.run` 是单工具模式的**总控方法**。当 `decideMode` 返回 `"tool"` 后，它接管一切：决策用哪个工具、找到工具对象、补参数、执行工具、把结果交给 LLM 生成自然语言。

它是单工具链路的"集装箱"——五步都在里面，没有其他方法参与编排。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ToolModeHandler.java:31-64
 *
 * 参数：
 *   resp     - 响应对象（会被修改：写入 answer 和 toolCall）
 *   query    - 用户原始输入
 *   ts       - 本轮可用工具集（Map<String, Tool>）
 *   memPrefix- 记忆前缀（偏好 + 长期记忆）
 *   histMsgs - 历史消息列表（短期记忆转 LLM 格式）
 *
 * 返回：void（通过 resp.setAnswer 写结果）
 */
public void run(ChatResponse resp, String query, Map<String, Tool> ts,
                String memPrefix, List<Map<String, String>> histMsgs) {

    // ████████ 第一步：决策工具 ████████
    ToolCallResult tc = toolService.decide(query, ts);            // ① 第13章详讲
    if (tc == null) {                                             // ② ts 为空时 decide 返回 null
        resp.setAnswer("我无法处理这个请求。");
        return;
    }

    // ████████ 第二步：校验工具存在 ████████
    Tool tool = ts.get(tc.getToolName());                         // ③ 从工具集查工具定义
    if (tool == null) {                                           // ④ 防御：万一 decide 返回了不存在的工具名
        resp.setAnswer("工具 " + tc.getToolName() + " 不存在");
        resp.setToolCall(tc);
        return;
    }

    // ████████ 第三步：偏好补参 ████████
    PreferenceFiller.fill(tc, pref);                              // ⑤ 第14章详讲

    // ████████ 第四步：执行工具 ████████
    try {
        String result = tool.getExecute().apply(tc.getParams());  // ⑥ 第15章详讲
        tc.setToolResult(result);                                 // ⑦ 结果写回 ToolCallResult
    } catch (Exception e) {
        resp.setAnswer("工具执行失败: " + e.getMessage());         // ⑧ 异常兜底
        resp.setToolCall(tc);
        return;
    }

    // ████████ 第五步：LLM 总结 ████████
    String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,   // ⑨ 用记忆前缀构造系统提示
            "你是一个善于综合信息的AI助手。结合你掌握的用户信息，使回答更个性化。");
    String userMsg = String.format(                                // ⑩ 构造 LLM 输入
            "用户问：%s\n工具 %s 返回结果：%s\n请根据结果自然地回答用户。",
            query, tc.getToolName(), tc.getToolResult());
    resp.setAnswer(llm.chat(sp, List.of(Map.of("role", "user", "content", userMsg)))); // ⑪ LLM 生成

    resp.setToolCall(tc);                                         // ⑫ 写入响应
}
```
### 2.1 逐行解释

下面按照源码里的编号，把单工具链路拆开。重点看 `ToolCallResult` 是怎么从“只有工具名和参数”变成“带工具执行结果”的。

**① 第13章详讲**

调用 `toolService.decide(query, ts)`，根据用户原始问题和本轮可用工具集，决策出一个 `ToolCallResult`。这一步只做两件事：选工具名、准备初始参数。比如 `"上海天气怎么样？"` 会得到 `ToolCallResult("get_weather", {city:"上海"})`；如果用户只说 `"今天天气怎么样？"`，天气分支可能得到 `{}`，后面交给 `PreferenceFiller.fill` 补。

**② ts 为空时 decide 返回 null**

如果 `tc == null`，说明 `decide` 没有选出任何工具。最典型原因是 `ts` 为空。这里直接把回答设成 `"我无法处理这个请求。"` 并 `return`，后面的取工具、补参、执行工具都不会发生。

**③ 从工具集查工具定义**

`tc.getToolName()` 只是一个字符串，例如 `"get_weather"`。真正能执行的是 `Tool` 对象，所以这里用工具名从 `ts` 里取出工具定义。这个 `Tool` 里包含 `description`、`parameters` 和最重要的 `execute` 函数。

**④ 防御：万一 decide 返回了不存在的工具名**

如果 `tool == null`，说明决策结果和工具集不一致：`decide` 返回了某个工具名，但 `ts` 里没有这个工具。这里把错误写入 `resp.answer`，同时 `resp.setToolCall(tc)` 保留决策记录，方便前端或调试时看到“本来想调用哪个工具”。

**⑤ 第14章详讲**

这是参数补全的关键位置。`PreferenceFiller.fill(tc, pref)` 会直接修改 `tc.params`。它只补缺失参数，不覆盖已有参数：如果 `tc.params` 已经有 `city:"上海"`，偏好里的默认城市不会覆盖；如果 `tc.params` 没有 `city`，偏好里有 `"城市" -> "上海"`，就会写入 `city:"上海"`。

**⑥ 第15章详讲**

这里才是真正执行工具。`tool.getExecute()` 取出工具注册时绑定的 lambda，`apply(tc.getParams())` 把补全后的参数传进去。对天气工具来说，就是把 `{city:"上海"}` 传给 `createGetWeatherTool` 里的执行函数。

**⑦ 结果写回 ToolCallResult**

工具返回的是原始结果字符串，比如 `"上海：小雨 20°C"`。这一行把它写进 `tc.toolResult`。到这里，`ToolCallResult` 才完整：既有 `toolName`，也有 `params`，还有 `toolResult`。

**⑧ 异常兜底**

如果工具执行抛异常，就不再让 LLM 总结，因为没有可靠的工具结果。方法把错误信息写入 `resp.answer`，把当前 `tc` 写入 `resp.toolCall`，然后返回。这样前端仍然能看到工具名和参数，只是 `toolResult` 没成功写入。

**⑨ 用记忆前缀构造系统提示**

工具已经执行完，接下来要让 LLM 把原始工具结果说成人话。`memPrefix` 会拼进 system prompt，让回答能结合用户偏好和长期记忆。但注意：这里的 `memPrefix` 不参与工具参数补全，参数补全已经在第 ⑤ 步完成。

**⑩ 构造 LLM 输入**

把三类信息塞进一个用户消息：原始问题、工具名、工具原始结果。LLM 看到的不是单纯 `"上海：小雨 20°C"`，而是“用户问什么 + 哪个工具返回了什么 + 请自然回答”，这样它知道如何组织最终回答。

**⑪ LLM 生成**

调用 `llm.chat` 生成最终答案，并写入 `resp.answer`。这里传入的是单条新构造的 `userMsg`，不是 `histMsgs`。所以单工具模式的最终回答主要基于“本轮问题 + 工具结果 + 记忆前缀”。

**⑫ 写入响应**

把完整的 `ToolCallResult` 写入响应。前端拿到后可以展示：调用了哪个工具、传了什么参数、工具返回了什么结果。这也是调试工具链路最重要的对象。


## 3. 参数逐个解释

| 参数 | 类型 | 来源 | 可修改 |
|---|---|---|---|
| `resp` | `ChatResponse` | `processInternal` 创建 | **是**——写入 answer、toolCall |
| `query` | `String` | 用户输入 | 否 |
| `ts` | `Map<String, Tool>` | `processInternal` 的 toolset | 否 |
| `memPrefix` | `String` | `buildMemorySystemPrefixWithCtx` | 否 |
| `histMsgs` | `List<Map<String,String>>` | `ChatHistoryAdapter.buildHistory` | 否 |

**`resp` 是唯一的"输出通道"**。这个方法没有返回值，所有结果通过 `resp.setAnswer`、`resp.setToolCall` 写回。

**`histMsgs` 在单工具模式下没有被用到**。注意第 ⑪ 行：LLM 调用传的是全新的 `userMsg`（包含工具结果），不是 `histMsgs`。`histMsgs` 只在 chat 模式和 ReAct 模式下被传给 LLM。

## 4. 返回值/副作用解释

**返回值**：`void`

**副作用**：

| 操作 | 位置 | 说明 |
|---|---|---|
| `resp.setAnswer(...)` | 第 35、41、52、62 行 | 写入最终答案或错误信息 |
| `resp.setToolCall(tc)` | 第 43、53、63 行 | 写入完整调用记录，供前端展示 |
| `tc.setToolResult(...)` | 第 50 行 | 工具的原始结果写回 ToolCallResult |
| `PreferenceFiller.fill(tc, pref)` | 第 46 行 | **可能修改 tc.params**——这是本方法最重要的副作用 |

**三种异常出口**：

```text
decide 返回 null     → "我无法处理这个请求。"
ts.get 返回 null     → "工具 xxx 不存在"
execute.apply 抛异常  → "工具执行失败: <异常消息>"
```

## 5. 这一步在完整链路中的位置

```text
processInternal
  ├── 构造 memPrefix, histMsgs
  ├── decideMode → "tool"
  ├── switch("tool") → toolHandler.run(resp, query, toolset, memPrefix, histMsgs)
  │                                                    ← 你在这里
  │   内部五步：
  │     ① toolService.decide(query, ts)        → ToolCallResult   (第13章)
  │     ② ts.get(tc.getToolName())              → Tool 对象        (本章已讲)
  │     ③ PreferenceFiller.fill(tc, pref)       → 补参数           (第14章)
  │     ④ tool.getExecute().apply(params)      → 工具结果          (第15章)
  │     ⑤ llm.chat(sp, userMsg)               → 自然语言回答       (第16章相关)
  ├── 写回 stm + 异步记忆
  └── 返回 resp
```

## 6. 用"上海天气怎么样？"跑一遍

```text
输入：
  query = "上海天气怎么样？"
  ts = {get_time, get_weather, search_web, rag_search, exec_command}
  memPrefix = "用户默认城市：上海"
  histMsgs = [{role:"user", content:"上海天气怎么样？"}]

═══════ 第一步：决策 ═══════
toolService.decide("上海天气怎么样？", ts)
  → q.toLowerCase() = "上海天气怎么样？"
  → "天气" 命中 + ts 有 get_weather
  → 遍历城市："上海" 命中
  → params = {city: "上海"}
  → return new ToolCallResult("get_weather", {city: "上海"})

tc = {
  toolName: "get_weather"
  params: {city: "上海"}
  toolResult: null
}

═══════ 第二步：校验 ═══════
ts.get("get_weather")
  → 返回 Tool 对象（启动时 createGetWeatherTool 创建的）
  → tool != null ✓

═══════ 第三步：补参 ═══════
PreferenceFiller.fill(tc, pref)
  → "城市" → 候选参数 [city, location, location_name]
  → pref 中有 城市="上海"？假设有
  → tc.params.city = "上海"（已存在，非空）
  → 不覆盖
tc.params 不变

═══════ 第四步：执行 ═══════
tool.getExecute().apply({city: "上海"})
  → city = "上海"（不为 null，跳过默认"北京"）
  → WEATHER_DB.get("上海") → "小雨 20°C"
  → 返回 "上海：小雨 20°C"
tc.setToolResult("上海：小雨 20°C")

═══════ 第五步：总结 ═══════
sp = "用户默认城市：上海\n\n你是一个善于综合信息的AI助手..."
userMsg = """
  用户问：上海天气怎么样？
  工具 get_weather 返回结果：上海：小雨 20°C
  请根据结果自然地回答用户。"""
llm.chat(sp, [userMsg])
  → "上海目前小雨，约 20°C，出门建议带伞。"

resp.answer = "上海目前小雨，约 20°C，出门建议带伞。"
resp.toolCall = tc（完整记录）
```

## 7. 常见误解

**误解一："ToolModeHandler.run 里五步顺序可以调换"**

不能。每一步依赖前一步的输出：
- 第 ② 步 `ts.get()` 需要第 ① 步产出的 `toolName`
- 第 ③ 步 `fill()` 需要第 ① 步产出的 `params`（修改它）
- 第 ④ 步 `execute.apply()` 需要第 ③ 步补完后的 `params`
- 第 ⑤ 步 `llm.chat()` 需要第 ④ 步产出的 `toolResult`

**误解二："ToolModeHandler 构造时传了 pref，所以它自己抽取偏好"**

`ToolModeHandler` 从构造函数拿到 `pref`（`PreferenceMemory`）对象，但它不调用 `pref.extractAndSave(query)`——偏好抽取已经在 `processInternal` 里做过了。`pref` 在这里只用于 `PreferenceFiller.fill(tc, pref)`：从偏好表里读已有偏好来补参数。

**误解三："resp.setToolCall(tc) 在 try-catch 外，所以异常时 tc 不会被写入"**

不对。注意第 43 行和第 53 行——`ts.get` 返回 null 和 `execute.apply` 抛异常时，`resp.setToolCall(tc)` 都在 `return` 之前执行。所以即使出错，前端也能看到 `ToolCallResult`（toolResult 为 null）。

**误解四："第五步的 LLM 调用用的是 histMsgs"**

没用。用的是全新的单条消息 `userMsg`。这意味着单工具模式**不传对话历史给 LLM**——LLM 只看"用户问题 + 工具结果 + 请总结"。这是一种简化设计：工具结果本身已经足够生成好答案，历史消息反而可能引入干扰。

**误解五："如果 ts.get 返回的 tool 是 null，PreferenceFiller 不会被调用"**

是的——`ts.get` 的 null 检查在 `fill` 之前。这是正确的：如果工具不存在，就不该补参数。
