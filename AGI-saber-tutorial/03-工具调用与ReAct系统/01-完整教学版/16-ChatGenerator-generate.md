# 16-ChatGenerator-generate

## 1. 这个方法解决什么问题

`ToolModeHandler.run` 里，工具结果已经拿到了——`"上海：小雨 20°C"`。但用户不能直接看这个，需要一句自然的回答。

`ChatGenerator.generate` 就是做这件事的：**把一堆工具观察结果（observations）综合成用户可读的自然语言回答**。

但有个关键点：**单工具模式下不用 `generate`**。`ToolModeHandler.run` 是自己构造 userMsg 然后调 `llm.chat`。`generate` 是给 ReAct 模式用的——当一个请求产生了多个 observations 时，才需要它来综合。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ChatGenerator.java:24-50
 *
 * 参数：
 *   query        - 用户原始问题
 *   observations - 工具节点执行结果列表（每个元素是 "[get_weather] 上海：小雨 20°C"）
 *   memPrefix    - 记忆前缀（偏好 + 长期记忆）
 *   histMsgs     - 历史消息列表（短期记忆转 LLM 格式）
 *
 * 返回：LLM 综合后的自然语言回答
 */
public String generate(String query, List<String> observations,
                       String memPrefix, List<Map<String, String>> histMsgs) {

    // ===== 情况 1：没有观察结果 =====
    if (observations == null || observations.isEmpty()) {          // ① 没有工具结果
        String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
        return llm.chat(sp, histMsgs);                            // ② 直接用历史消息调 LLM
    }

    // ===== 情况 2：mock 模式 =====
    if (!cfg.isRealLLM()) {                                       // ③ mock 模式：不调 LLM
        return "综合查询结果：" + String.join("；", observations);   // ④ 直接拼接 observations
    }

    // ===== 情况 3：正常 LLM 综合 =====
    StringBuilder obs = new StringBuilder();
    for (int i = 0; i < observations.size(); i++) {               // ⑤ 给每条 observation 编号
        obs.append(i + 1).append(". ").append(observations.get(i)).append("\n");
    }

    String genPrompt = String.format("""
            请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

            用户问题：%s

            工具执行结果：
            %s""", query, obs);                                   // ⑥ 构造综合 prompt

    String generatorBase = "你是一个善于综合信息的AI助手，"
        + "能将多个工具的执行结果整合成清晰自然的回答。";
    if (memPrefix != null && !memPrefix.isEmpty()) {               // ⑦ 如果有记忆前缀，注入
        generatorBase = memPrefix + "\n\n" + generatorBase
            + "\n结合用户偏好，使回答更个性化。";
    }
    return llm.chat(generatorBase,                                 // ⑧ 调 LLM
        List.of(Map.of("role", "user", "content", genPrompt)));
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 没有工具结果**

没有工具结果。`observations` 为空时，Generator 无法基于工具观察总结，只能退回普通对话路径。

**② 直接用历史消息调 LLM**

直接用历史消息调 LLM。这是无 observation 的 fallback，回答主要依赖 `memPrefix` 和 `histMsgs`。

**③ mock 模式：不调 LLM**

mock 模式：不调 LLM。测试环境下直接拼接 observations，保证不用真实模型也能验证 ReAct 链路。

**④ 直接拼接 observations**

直接拼接 observations。当没有 observation 时，Generator 不需要再调用工具，只能基于空结果生成回答或兜底说明。

**⑤ 给每条 observation 编号**

给每条 observation 编号。编号能让 LLM 区分多条工具观察，避免把不同工具的结果混在一起。

**⑥ 构造综合 prompt**

构造综合 prompt。这一步先准备容器或对象，后面的循环、写入或返回都依赖它。

**⑦ 如果有记忆前缀，注入**

如果有记忆前缀，注入。这一步是在为 LLM 或 JSON 解析准备输入/输出格式，格式不稳定会直接影响后续解析和执行。

**⑧ 调 LLM**

调 LLM。把用户问题和所有工具观察一起交给模型，让模型生成最终面向用户的答案。


## 3. 参数逐个解释

| 参数 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `query` | `String` | 用户原始输入 | LLM 需要知道原始问题来组织回答 |
| `observations` | `List<String>` | `TaskGraph.successfulResults()` | 每条格式如 `[get_weather] 上海：小雨 20°C` |
| `memPrefix` | `String` | `buildMemorySystemPrefixWithCtx` | 偏好 + 长期记忆，用于个性化回答 |
| `histMsgs` | `List<Map<String,String>>` | `ChatHistoryAdapter.buildHistory` | 只在 observation 为空时使用 |

**`observations` 的来源**（第 30 章详讲）：

```java
// TaskGraph.successfulResults():
// 只收集 status=DONE 的节点结果，格式为 "[toolName] result"
// 失败的、跳过的不包含在内
```

## 4. 返回值/副作用解释

**返回值**：`String`——LLM 综合后的自然语言回答。

**三种情况的不同返回值**：

| 情况 | 条件 | 返回 | 示例 |
|---|---|---|---|
| 无观察 | `observations` 为空 | `llm.chat(sp, histMsgs)` | 纯 LLM 回答 |
| Mock 模式 | `!cfg.isRealLLM()` | `"综合查询结果：" + 拼接` | `"综合查询结果：[get_weather] 上海：小雨 20°C；[search_web] 雨天建议..."` |
| 正常综合 | 有观察 + 真实 LLM | `llm.chat(generatorBase, genPrompt)` | LLM 生成的流畅回答 |

**正常综合的 prompt 结构**：

```text
system: "用户默认城市：上海\n\n你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。\n结合用户偏好，使回答更个性化。"

user: "请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

用户问题：查一下上海天气，并搜索小雨出门建议

工具执行结果：
1. [get_weather] 上海：小雨 20°C
2. [search_web] 小雨天出行建议：带好雨具，注意路面湿滑..."
```

## 5. 这一步在完整链路中的位置

**在单工具模式下不用 `generate`**：

```text
ToolModeHandler.run:
  ④ tool.getExecute().apply(params) → toolResult
  ⑤ llm.chat(sp, userMsg)            ← 自己调 LLM，不用 ChatGenerator

原因：单工具只有一个结果，不需要"综合多个 observations"
```

**在 ReAct 模式下用 `generate`**：

```text
ReActLoop.runStream:
  ① Planner.planGraph → nodes
  ② new TaskGraph(nodes)
  ③ GraphRuntime.execute → GraphResult(observations, nodeResults)
  ④ ChatGenerator.generate(query, observations, memPrefix, histMsgs)
      ↑ 你在这里
  ⑤ resp.setAnswer(...)
```

## 6. 用"查一下上海天气，并搜索小雨出门建议"跑一遍

```text
输入：
  query = "查一下上海天气，并搜索小雨出门建议"
  observations = [
    "[get_weather] 上海：小雨 20°C",
    "[search_web] 小雨天出行建议：带好雨具，注意路面湿滑..."
  ]
  memPrefix = "用户默认城市：上海"
  histMsgs = [...]

═══════ observations 非空 ✓，cfg.isRealLLM() = true ═══════

⑤ 编号：
  "1. [get_weather] 上海：小雨 20°C\n2. [search_web] 小雨天出行建议：...\n"

⑥ genPrompt:
  "请根据以下工具执行结果，综合回答用户的问题。
   ...
   用户问题：查一下上海天气，并搜索小雨出门建议
   工具执行结果：
   1. [get_weather] 上海：小雨 20°C
   2. [search_web] 小雨天出行建议：...\n"

⑦ generatorBase:
  "用户默认城市：上海\n\n你是一个善于综合信息的AI助手...\n结合用户偏好，使回答更个性化。"

⑧ llm.chat(generatorBase, genPrompt)
  → LLM 综合输出

返回："上海目前小雨，约 20°C。小雨天出行建议带伞、穿防滑鞋、注意路面湿滑。"
```

## 7. 常见误解

**误解一："ChatGenerator 在单工具和 ReAct 两种模式下都被调用"**

不是。单工具的总结在 `ToolModeHandler.run` 里内联完成——直接调用 `llm.chat(sp, userMsg)`，不走 `ChatGenerator.generate`。`generate` 只在 ReAct 模式下被 `ReActLoop.runStream` 调用。

**误解二："observations 包含所有节点的结果"**

不是。observations 只包含 `status=DONE` 的节点结果。失败、跳过、中断的节点不会被包含——因为只有成功的工具结果才有参考价值。详见第 30 章 `TaskGraph.successfulResults`。

**误解三："generate 在 observations 为空时直接失败"**

不会。第 ① 行做了兜底——如果 observations 为空，绕过综合步骤，直接用历史消息调 LLM。这相当于退化成纯 chat 模式。

**误解四："genPrompt 里的 instruction 不重要，LLM 随便怎么回答都行"**

instruction 里的约束直接影响输出质量：
- `"不要机械罗列原始数据"` — 防止 LLM 把工具结果原样搬过来
- `"不要重复问题本身"` — 防止回答以 `"关于查一下上海天气并搜索小雨出门建议，以下是回答："` 开头

这些约束是针对 LLM 常见坏习惯的修正。

**误解五："memPrefix 注入 generatorBase 只是为了个性化"**

不只是个性化。`memPrefix` 可能包含"用户是软件工程师"这样的偏好——这会影响 LLM 组织回答的技术深度。比如同一个天气结果，给普通用户可能是"出门带伞"，给工程师可能是"当前上海小雨，约 20°C（数据来源：get_weather），建议查看气象雷达图确认降雨持续时间"。
