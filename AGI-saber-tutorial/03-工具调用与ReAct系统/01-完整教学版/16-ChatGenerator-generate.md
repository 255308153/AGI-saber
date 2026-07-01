# 16-ChatGenerator-generate

这一节讲 `ChatGenerator.generate(...)`。

工具调用完成以后，系统还不能直接把工具原始结果丢给用户。比如工具返回：

```text
[get_weather] 上海：小雨 20°C
[search_web] 小雨天出行建议：带伞，注意路面湿滑
```

这只是工具结果，不是最终回答。

`ChatGenerator.generate` 的作用就是：**把 ReAct 模式里多个工具/子 Agent 的成功结果，拼成一个综合提示词，再让 LLM 生成自然语言回答**。

注意：单工具模式不走这个方法。单工具模式在 `ToolModeHandler.run` 里自己拼提示词并调用 `llm.chat`。

## 1. 它在完整链路里的位置

ReAct 模式的后半段链路是：

```text
ReActLoop.runStream
  -> GraphRuntime.execute()
      -> 执行工具节点 / 子 Agent 节点
      -> GraphRuntime.buildResult()
      -> GraphResult.observations
  -> generator.generate(query, gr.observations, memPrefix, histMsgs)
      -> 拼 obs 编号列表
      -> 拼 genPrompt
      -> 拼 generatorBase
      -> llm.chat(generatorBase, messages)
  -> resp.setAnswer(answer)
```

所以最终回答不是 `GraphRuntime` 生成的。

`GraphRuntime` 只负责执行图，产出 `observations`。

真正把 `observations` 变成用户回答的是：

```java
String answer = generator.generate(query, gr.observations, memPrefix, histMsgs);
resp.setAnswer(answer);
```

这两行在 `ReActLoop.runStream` 里。

## 2. 完整源码

源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/application/chat/ChatGenerator.java
```

```java
public String generate(String query, List<String> observations,
                       String memPrefix, List<Map<String, String>> histMsgs) {

    // ① 如果没有工具结果，就不走“工具综合回答”。
    //
    // observations == null：
    //   调用方没有传工具结果列表。
    //
    // observations.isEmpty()：
    //   传了列表，但是里面没有任何成功工具结果。
    //
    // 这时系统退回普通聊天：用 memPrefix 拼 system prompt，
    // 再把 histMsgs 作为历史对话交给 LLM。
    if (observations == null || observations.isEmpty()) {
        String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
        return llm.chat(sp, histMsgs);
    }

    // ② 如果当前不是 Real LLM 模式，就不调用真实模型。
    //
    // cfg.isRealLLM() == false 通常用于本地演示、测试、没有配置模型的时候。
    //
    // 这里直接把 observations 用中文分号连接起来，返回一个确定的字符串。
    // 这样不用真实 LLM，也能验证前面的 Planner、GraphRuntime、工具执行链路是否通了。
    if (!cfg.isRealLLM()) {
        return "综合查询结果：" + String.join("；", observations);
    }

    // ③ 把 observations 转成带编号的文本。
    //
    // 原始 observations 是 List<String>：
    //   [
    //     "[get_weather] 上海：小雨 20°C",
    //     "[search_web] 小雨天出行建议：带伞，注意路面湿滑"
    //   ]
    //
    // 转换后 obs 是一个 StringBuilder：
    //   1. [get_weather] 上海：小雨 20°C
    //   2. [search_web] 小雨天出行建议：带伞，注意路面湿滑
    //
    // 编号的目的：让 LLM 知道这是多条独立证据，不要把不同工具结果混成一条。
    StringBuilder obs = new StringBuilder();
    for (int i = 0; i < observations.size(); i++) {
        obs.append(i + 1).append(". ").append(observations.get(i)).append("\n");
    }

    // ④ 拼 user prompt，也就是发给 LLM 的用户消息 content。
    //
    // genPrompt 里有三块：
    //   1. 回答要求：自然流畅、重点突出、不要机械罗列
    //   2. 用户问题：query
    //   3. 工具执行结果：obs
    //
    // LLM 会根据这段 user prompt 生成最终回答。
    String genPrompt = String.format("""
            请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

            用户问题：%s

            工具执行结果：
            %s""", query, obs);

    // ⑤ 拼 system prompt。
    //
    // generatorBase 告诉 LLM 它现在扮演什么角色：
    //   “你是一个善于综合信息的AI助手...”
    //
    // system prompt 负责定角色、定风格；
    // user prompt 负责放本次问题和工具结果。
    String generatorBase = "你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。";

    // ⑥ 如果有记忆前缀，把记忆也放进 system prompt。
    //
    // memPrefix 可能包含：
    //   用户默认城市：上海
    //   用户偏好：回答尽量简洁
    //
    // 拼完后 generatorBase 变成：
    //
    //   用户默认城市：上海
    //   用户偏好：回答尽量简洁
    //
    //   你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。
    //   结合用户偏好，使回答更个性化。
    if (memPrefix != null && !memPrefix.isEmpty()) {
        generatorBase = memPrefix + "\n\n" + generatorBase + "\n结合用户偏好，使回答更个性化。";
    }

    // ⑦ 真正调用 LLM。
    //
    // 第一个参数 generatorBase 是 system prompt。
    //
    // 第二个参数是 messages，这里只放了一条 user 消息：
    //   {
    //     "role": "user",
    //     "content": genPrompt
    //   }
    //
    // LLM 返回的字符串就是最终 answer。
    return llm.chat(generatorBase, List.of(Map.of("role", "user", "content", genPrompt)));
}
```

## 3. 参数从哪里来

方法签名：

```java
public String generate(String query, List<String> observations,
                       String memPrefix, List<Map<String, String>> histMsgs)
```

四个参数分别是：

| 参数 | 类型 | 来源 | 在这里怎么用 |
|---|---|---|---|
| `query` | `String` | 用户本次输入 | 拼进 `genPrompt` 的“用户问题”部分 |
| `observations` | `List<String>` | `GraphResult.observations` | 编号后拼进 `genPrompt` 的“工具执行结果”部分 |
| `memPrefix` | `String` | 记忆系统生成的前缀 | 拼进 `generatorBase`，影响回答风格和个性化信息 |
| `histMsgs` | `List<Map<String,String>>` | 短期历史消息 | 只在 `observations` 为空时使用 |

重点是 `observations`。

它不是用户输入，也不是 LLM 自己生成的内容，而是图执行结束后收集到的成功节点结果。

例如：

```text
observations = [
  "[get_weather] 上海：小雨 20°C",
  "[search_web] 小雨天出行建议：带伞，注意路面湿滑"
]
```

这个列表来自：

```java
r.observations = graph.successfulResults();
```

`successfulResults()` 只收集 `status=DONE` 的节点。失败节点、跳过节点、中断节点不会进入 `observations`。

## 4. 第一步：没有 observations 怎么办

源码：

```java
if (observations == null || observations.isEmpty()) {
    String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
            "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
    return llm.chat(sp, histMsgs);
}
```

这段代码处理的是“没有工具结果”的情况。

可能出现两种输入：

```text
observations = null
```

或者：

```text
observations = []
```

这时没有任何工具结果可以综合，所以代码不拼 `genPrompt`，直接走普通聊天。

如果：

```text
memPrefix = "用户偏好：回答简洁"
```

那么：

```java
ChatHistoryAdapter.buildSystemPrompt(memPrefix, basePrompt)
```

会得到：

```text
用户偏好：回答简洁

你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。
```

然后调用：

```java
llm.chat(sp, histMsgs)
```

这里的 `histMsgs` 是历史对话列表，例如：

```text
[
  {"role":"user","content":"你好"},
  {"role":"assistant","content":"你好，有什么可以帮你？"},
  {"role":"user","content":"介绍一下这个项目"}
]
```

这个分支的含义是：**没有工具结果时，就把它当成普通聊天回答**。

## 5. 第二步：Mock 模式怎么返回

源码：

```java
if (!cfg.isRealLLM()) {
    return "综合查询结果：" + String.join("；", observations);
}
```

`cfg.isRealLLM()` 表示当前是否使用真实 LLM。

如果不是 Real LLM 模式，代码不会调用模型。

输入：

```text
observations = [
  "[get_weather] 上海：小雨 20°C",
  "[search_web] 小雨天出行建议：带伞，注意路面湿滑"
]
```

执行：

```java
String.join("；", observations)
```

结果：

```text
[get_weather] 上海：小雨 20°C；[search_web] 小雨天出行建议：带伞，注意路面湿滑
```

最终返回：

```text
综合查询结果：[get_weather] 上海：小雨 20°C；[search_web] 小雨天出行建议：带伞，注意路面湿滑
```

这个分支适合测试。它证明前面工具确实执行了，但不会生成很自然的用户回答。

## 6. 第三步：把 observations 编号

源码：

```java
StringBuilder obs = new StringBuilder();
for (int i = 0; i < observations.size(); i++) {
    obs.append(i + 1).append(". ").append(observations.get(i)).append("\n");
}
```

这里创建了一个 `StringBuilder`。

`StringBuilder` 可以理解成“可不断追加内容的字符串容器”。普通字符串每次拼接都可能产生新字符串，`StringBuilder` 更适合在循环里一段一段追加文本。

假设原始输入是：

```text
observations = [
  "[get_weather] 上海：小雨 20°C",
  "[search_web] 小雨天出行建议：带伞，注意路面湿滑",
  "[rag_search] 公司差旅制度：雨天可申请打车报销"
]
```

循环过程是：

第一次循环：

```text
i = 0
i + 1 = 1
observations.get(0) = "[get_weather] 上海：小雨 20°C"
obs 追加：
1. [get_weather] 上海：小雨 20°C\n
```

第二次循环：

```text
i = 1
i + 1 = 2
observations.get(1) = "[search_web] 小雨天出行建议：带伞，注意路面湿滑"
obs 追加：
2. [search_web] 小雨天出行建议：带伞，注意路面湿滑\n
```

第三次循环：

```text
i = 2
i + 1 = 3
observations.get(2) = "[rag_search] 公司差旅制度：雨天可申请打车报销"
obs 追加：
3. [rag_search] 公司差旅制度：雨天可申请打车报销\n
```

循环结束后，`obs.toString()` 等价于：

```text
1. [get_weather] 上海：小雨 20°C
2. [search_web] 小雨天出行建议：带伞，注意路面湿滑
3. [rag_search] 公司差旅制度：雨天可申请打车报销
```

为什么要编号？

因为 LLM 看到编号后更容易理解：

```text
第 1 条是天气工具结果
第 2 条是网页搜索结果
第 3 条是知识库结果
```

这样它综合回答时会更稳定，不容易漏掉其中一条。

## 7. 第四步：genPrompt 是怎么拼出来的

源码：

```java
String genPrompt = String.format("""
        请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

        用户问题：%s

        工具执行结果：
        %s""", query, obs);
```

这里用了 Java 的文本块 `""" ... """`。

它的意思是：保留多行字符串格式，不需要自己手写很多 `\n`。

这个字符串里有两个 `%s`：

```text
用户问题：%s
工具执行结果：
%s
```

`String.format(..., query, obs)` 会按顺序替换：

```text
第一个 %s  <- query
第二个 %s  <- obs
```

假设：

```text
query = "查一下上海天气，并搜索小雨出门建议"
```

并且：

```text
obs =
1. [get_weather] 上海：小雨 20°C
2. [search_web] 小雨天出行建议：带伞，注意路面湿滑
3. [rag_search] 公司差旅制度：雨天可申请打车报销
```

那么 `genPrompt` 最终就是：

```text
请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

用户问题：查一下上海天气，并搜索小雨出门建议

工具执行结果：
1. [get_weather] 上海：小雨 20°C
2. [search_web] 小雨天出行建议：带伞，注意路面湿滑
3. [rag_search] 公司差旅制度：雨天可申请打车报销
```

这段提示词的每一块都有用：

| 片段 | 作用 |
|---|---|
| `请根据以下工具执行结果` | 明确告诉 LLM：答案要基于工具结果，不是凭空猜 |
| `综合回答用户的问题` | 要求 LLM 做整合，不是逐条复读 |
| `回答要自然流畅、重点突出` | 控制输出像正常回答 |
| `不要机械罗列原始数据` | 防止输出变成“1... 2... 3...” |
| `也不要重复问题本身` | 防止输出开头重复“你问的是...” |
| `用户问题：%s` | 让 LLM 知道最终要回答哪个问题 |
| `工具执行结果：%s` | 给 LLM 提供可引用的信息来源 |

所以 `genPrompt` 是“本次任务内容”。

它告诉 LLM：

```text
用户问了什么
工具查到了什么
你应该按什么风格整理
```

## 8. 第五步：generatorBase 是什么

源码：

```java
String generatorBase = "你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。";
if (memPrefix != null && !memPrefix.isEmpty()) {
    generatorBase = memPrefix + "\n\n" + generatorBase + "\n结合用户偏好，使回答更个性化。";
}
```

`generatorBase` 是 system prompt。

system prompt 不是本次问题，而是告诉模型“你是谁、回答时应该遵守什么角色和风格”。

如果没有记忆：

```text
memPrefix = ""
```

那么：

```text
generatorBase =
你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。
```

如果有记忆：

```text
memPrefix =
用户默认城市：上海
用户偏好：回答尽量简洁
```

那么拼接后：

```text
generatorBase =
用户默认城市：上海
用户偏好：回答尽量简洁

你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。
结合用户偏好，使回答更个性化。
```

这里的 `\n\n` 是两个换行，用来把记忆和基础角色提示分开。

最后追加的：

```text
结合用户偏好，使回答更个性化。
```

意思是提醒模型：上面的记忆不是摆设，要用进回答风格里。

## 9. 第六步：llm.chat 最终收到什么

源码：

```java
return llm.chat(generatorBase, List.of(Map.of("role", "user", "content", genPrompt)));
```

`llm.chat` 收到两个参数：

| 参数 | 实际内容 | 作用 |
|---|---|---|
| `generatorBase` | system prompt 字符串 | 定角色、定风格、注入记忆 |
| `List.of(Map.of(...))` | 消息列表 | 这里放一条 user 消息，内容是 `genPrompt` |

第二个参数完整结构是：

```text
[
  {
    "role": "user",
    "content": "请根据以下工具执行结果，综合回答用户的问题...\n\n用户问题：...\n\n工具执行结果：..."
  }
]
```

如果套入前面的天气例子，真实传入类似：

```text
system:
用户默认城市：上海
用户偏好：回答尽量简洁

你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。
结合用户偏好，使回答更个性化。

messages:
[
  {
    "role": "user",
    "content": "请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。\n\n用户问题：查一下上海天气，并搜索小雨出门建议\n\n工具执行结果：\n1. [get_weather] 上海：小雨 20°C\n2. [search_web] 小雨天出行建议：带伞，注意路面湿滑\n3. [rag_search] 公司差旅制度：雨天可申请打车报销\n"
  }
]
```

LLM 根据这两个部分生成最终回答，例如：

```text
上海现在是小雨，气温约 20°C。出门建议带伞，路面湿滑时注意防滑；如果这是公司差旅行程，雨天打车可以按差旅制度申请报销。
```

这个返回值会一路回到：

```java
String answer = generator.generate(query, gr.observations, memPrefix, histMsgs);
resp.setAnswer(answer);
```

也就是用户最后看到的 `answer`。

## 10. 单工具模式是怎么总结的

单工具模式不走 `ChatGenerator.generate`。

它在 `ToolModeHandler.run` 里直接拼：

```java
String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
        "你是一个善于综合信息的AI助手。结合你掌握的用户信息，使回答更个性化。");
String userMsg = String.format(
        "用户问：%s\n工具 %s 返回结果：%s\n请根据结果自然地回答用户。",
        query, tc.getToolName(), tc.getToolResult());
resp.setAnswer(llm.chat(sp, List.of(Map.of("role", "user", "content", userMsg))));
```

如果：

```text
query = "上海天气怎么样"
tc.getToolName() = "get_weather"
tc.getToolResult() = "上海：小雨 20°C"
memPrefix = "用户默认城市：上海"
```

那么 system prompt 是：

```text
用户默认城市：上海

你是一个善于综合信息的AI助手。结合你掌握的用户信息，使回答更个性化。
```

user message 是：

```text
用户问：上海天气怎么样
工具 get_weather 返回结果：上海：小雨 20°C
请根据结果自然地回答用户。
```

然后调用：

```java
llm.chat(sp, List.of(Map.of("role", "user", "content", userMsg)))
```

LLM 可能返回：

```text
上海现在小雨，气温约 20°C，出门记得带伞。
```

所以两条总结链路的区别是：

| 对比项 | 单工具模式 | ReAct 多工具模式 |
|---|---|---|
| 入口 | `ToolModeHandler.run` | `ReActLoop.runStream` |
| 工具结果数量 | 一个 `tc.toolResult` | 多个 `gr.observations` |
| 拼提示词的位置 | `ToolModeHandler.run` 里拼 `userMsg` | `ChatGenerator.generate` 里拼 `genPrompt` |
| 是否编号 | 不编号 | 给每条 observation 编号 |
| 调 LLM | `llm.chat(sp, userMsg)` | `llm.chat(generatorBase, genPrompt)` |
| 回填回答 | `resp.setAnswer(...)` | `resp.setAnswer(answer)` |

## 11. ReAct 多工具完整例子

用户问题：

```text
查一下上海天气，并搜索小雨出门建议
```

图执行结束后：

```text
gr.observations = [
  "[get_weather] 上海：小雨 20°C",
  "[search_web] 小雨天出行建议：带伞，注意路面湿滑"
]
```

调用：

```java
String answer = generator.generate(query, gr.observations, memPrefix, histMsgs);
```

进入 `generate` 后：

```text
observations 非空
cfg.isRealLLM() == true
```

先得到 `obs`：

```text
1. [get_weather] 上海：小雨 20°C
2. [search_web] 小雨天出行建议：带伞，注意路面湿滑
```

再得到 `genPrompt`：

```text
请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

用户问题：查一下上海天气，并搜索小雨出门建议

工具执行结果：
1. [get_weather] 上海：小雨 20°C
2. [search_web] 小雨天出行建议：带伞，注意路面湿滑
```

如果：

```text
memPrefix = "用户默认城市：上海"
```

再得到 `generatorBase`：

```text
用户默认城市：上海

你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。
结合用户偏好，使回答更个性化。
```

最终调用：

```java
llm.chat(generatorBase, List.of(Map.of("role", "user", "content", genPrompt)))
```

返回：

```text
上海现在小雨，气温约 20°C。出门建议带伞，路面湿滑时注意防滑，尽量穿防滑鞋或避开积水路段。
```

最后：

```java
resp.setAnswer(answer);
```

用户看到的就是这段自然语言回答。

## 12. 三种返回路径

`generate` 有三种返回路径：

| 路径 | 条件 | 是否调用真实 LLM | 返回内容 |
|---|---|---|---|
| 普通聊天兜底 | `observations == null || observations.isEmpty()` | 是 | `llm.chat(sp, histMsgs)` 的结果 |
| Mock 拼接 | `!cfg.isRealLLM()` | 否 | `"综合查询结果：" + String.join("；", observations)` |
| 工具综合回答 | 有 observations 且是真实 LLM | 是 | `llm.chat(generatorBase, messages)` 的结果 |

这三条路径互斥。

代码从上往下判断：

```text
先看有没有 observations
再看是不是 mock 模式
最后才进入真实 LLM 综合
```

## 13. 常见误解

**误解一：“工具调用完以后，GraphRuntime 直接总结回答。”**

不是。`GraphRuntime` 只执行工具/子 Agent，并把成功结果整理进 `GraphResult.observations`。总结回答发生在 `ReActLoop.runStream` 调用 `ChatGenerator.generate` 之后。

**误解二：“genPrompt 就是 system prompt。”**

不是。`genPrompt` 是 user message 的 `content`。

真正的 system prompt 是 `generatorBase`。

两者分工不同：

```text
generatorBase：你是谁、怎么回答、是否结合记忆
genPrompt：这次用户问什么、工具查到了什么
```

**误解三：“histMsgs 每次都会传给 LLM。”**

不是。在 `ChatGenerator.generate` 的真实工具综合分支里，`histMsgs` 没有被传给 `llm.chat`。

工具综合分支只传：

```java
List.of(Map.of("role", "user", "content", genPrompt))
```

`histMsgs` 只在 `observations` 为空的兜底分支使用。

**误解四：“observations 越原始越好，LLM 会自己看懂。”**

不是。代码先给 observations 编号，就是为了让多条工具结果变成清晰证据列表。否则多个工具结果挤在一起，模型更容易漏读或混读。

**误解五：“单工具和多工具最后调用 LLM 的方式完全一样。”**

不完全一样。

它们最后都是 `llm.chat(...)`，但提示词来源不同：

```text
单工具：ToolModeHandler.run 直接拼 userMsg
多工具：ChatGenerator.generate 把 observations 编号后拼 genPrompt
```

## 14. 一句话总结

`ChatGenerator.generate` 做的事就是：**拿到 `GraphRuntime` 产出的 `observations`，先把它们编号成证据列表，再拼出 `genPrompt` 和 `generatorBase`，最后调用 `llm.chat` 生成用户最终看到的自然语言答案**。
