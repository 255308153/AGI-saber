# 09-histMsgs如何进入LLM

## 1. 一句话结论

`histMsgs` 是短期记忆转换后的 LLM messages，它会作为 `llm.chat(systemPrompt, histMsgs)` 的第二个参数进入模型。

一句话分清：

```text
memPrefix 进入 system prompt
histMsgs   进入 messages
```

## 2. 在记忆系统里的位置

完整位置如下：

```text
ShortTermMemory.messages
  ↓
ChatHistoryAdapter.buildHistory(stm, query)
  ↓
histMsgs
  ↓
普通 chat / ReAct fallback / ChatGenerator
  ↓
llm.chat(systemPrompt, histMsgs)
```

在 `tool` 模式里，`ToolModeHandler.run` 接收了 `histMsgs` 参数，但当前真实代码最终综合回答时没有把 `histMsgs` 原样传给 `llm.chat`，而是重新构造了一条 `userMsg`。

这个点面试时要说准确。

## 3. 源码位置和核心对象

创建 `histMsgs` 的源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/service/agent/UnifiedAgentService.java
```

适配器源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/application/chat/ChatHistoryAdapter.java
```

下游使用位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/application/chat/ReActLoop.java
AGI-saber-java/src/main/java/com/agi/assistant/application/chat/ChatGenerator.java
AGI-saber-java/src/main/java/com/agi/assistant/application/chat/ToolModeHandler.java
```

`histMsgs` 是短期记忆进入 LLM 前的最终存在形式：

```text
List<Map<String, String>>
```

它不是数据库记录，也不是 `ConversationMessage` 对象。它是一次 LLM 调用临时构造出来的上下文参数。

调用结束后，真正长期保留的仍然是：

```text
ShortTermMemory.messages 内存列表
chat_history 数据库记录
```

## 4. 核心流程图

```mermaid
flowchart TD
    A["processInternal(query)"] --> B["stm.add(\"user\", query)"]
    B --> C["memPrefix = buildMemorySystemPrefixWithCtx(query)"]
    C --> D["histMsgs = ChatHistoryAdapter.buildHistory(stm, query)"]
    D --> E{"mode"}
    E -- "chat" --> F["llm.chat(sp, histMsgs)"]
    E -- "react 无工具计划" --> G["llm.chat(sp, histMsgs)"]
    E -- "react 有工具结果" --> H["ChatGenerator.generate(..., histMsgs)"]
    H --> I{"observations 为空?"}
    I -- "是" --> J["llm.chat(sp, histMsgs)"]
    I -- "否" --> K["用工具结果重组 genPrompt"]
    E -- "tool" --> L["ToolModeHandler.run(..., histMsgs)"]
    L --> M["当前代码重组 userMsg 后调用 llm.chat"]
```

## 5. 源码讲解

### 5.1 先说这段链路解决什么问题

`histMsgs` 解决的问题是：

```text
让大模型知道最近几轮对话发生了什么。
```

它不是长期记忆，也不是偏好记忆。

它就是：

```text
短期记忆转换后的最近聊天记录。
```

### 5.2 生活类比

你可以把一次 LLM 调用想成把两份材料交给一个人：

```text
第一份：任务说明书
  你是谁、要怎么回答、用户有什么长期偏好。
  这份对应 system prompt。

第二份：最近聊天记录
  用户刚才问了什么、助手刚才答了什么。
  这份对应 histMsgs。
```

所以：

```text
memPrefix 不是聊天记录，它更像“用户档案和长期提醒”。
histMsgs 不是用户档案，它更像“最近对话 transcript”。
```

### 5.3 对应到代码：先构造 memPrefix 和 histMsgs

```java
String memPrefix = buildMemorySystemPrefixWithCtx(query); // 构造记忆前缀，里面包含偏好和长期记忆召回结果
List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query); // 把短期记忆转换成 LLM messages
```

逐行解释：

```text
第 1 行：根据当前 query 召回偏好、长期记忆、图记忆等，拼成 memPrefix。
第 2 行：从 ShortTermMemory 里拿最近聊天记录，转换成 histMsgs。
```

这两个变量来源不同：

```text
memPrefix 来源：
  偏好记忆 + 长期记忆召回结果 + 图记忆召回结果

histMsgs 来源：
  ShortTermMemory.messages 最近几轮对话
```

### 5.4 对应到代码：普通 chat 模式怎么进入 LLM

```java
String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix, // 把偏好和长期记忆拼到 system prompt 前面
        "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。"); // 基础 system prompt
resp.setAnswer(llm.chat(sp, histMsgs)); // system prompt 作为第一个参数，短期历史 histMsgs 作为第二个参数
```

先说目的：

```text
把长期类信息放进 system prompt，
把最近聊天记录放进 messages，
然后调用 LLM 生成回答。
```

生活类比：

```text
sp 像写在试卷最上面的答题要求。
histMsgs 像附在试卷后面的最近聊天记录。
模型答题时，两份都会看，但位置不同。
```

逐行解释：

```text
第 1-2 行：buildSystemPrompt 把 memPrefix 和基础 prompt 拼成 sp。
第 3 行：llm.chat(sp, histMsgs) 调用大模型。
第 3 行：sp 是第一个参数，表示 system prompt。
第 3 行：histMsgs 是第二个参数，表示 messages。
第 3 行：resp.setAnswer(...) 把模型回答放进响应对象。
```

展开后结构是：

```text
llm.chat(
  sp,       // system prompt：规则、身份、偏好、长期记忆
  histMsgs  // messages：最近几轮 user/assistant 对话
)
```

### 5.5 对应到代码：ReAct 没有工具时怎么用 histMsgs

```java
String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
        "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。"); // 构造 system prompt
String answer = llm.chat(sp, histMsgs); // 没有工具可用时，直接用短期历史回答
```

先说目的：

```text
如果 ReAct 没有规划出工具调用，系统就退回普通聊天。
普通聊天仍然需要最近对话，所以继续传 histMsgs。
```

逐行解释：

```text
第 1-2 行：构造 system prompt。
第 3 行：把 sp 和 histMsgs 交给 llm.chat。
```

### 5.6 对应到代码：有工具结果时为什么不一定原样使用 histMsgs

ReAct 有工具结果时，会进入：

```java
String answer = generator.generate(query, gr.observations, memPrefix, histMsgs); // 把用户问题、工具观察结果、记忆前缀、短期历史交给生成器
```

先说目的：

```text
工具已经查到了结果后，系统要让 LLM 综合工具结果生成最终回答。
```

`ChatGenerator.generate` 里有两个分支。

如果没有工具结果：

```java
if (observations == null || observations.isEmpty()) { // 没有工具执行结果
    String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
            "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。"); // 构造 system prompt
    return llm.chat(sp, histMsgs); // 直接把短期历史传给 LLM
}
```

如果有工具结果：

```java
String genPrompt = String.format("""
        请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

        用户问题：%s

        工具执行结果：
        %s""", query, obs); // 把 query 和工具结果重新组织成一条新的 user message
return llm.chat(generatorBase, List.of(Map.of("role", "user", "content", genPrompt))); // 此分支没有原样传 histMsgs，而是传重新构造的 genPrompt
```

这里要准确理解：

```text
histMsgs 作为参数传进了 ChatGenerator.generate。
但如果 observations 不为空，最终 LLM 调用没有原样传 histMsgs。
它会把 query + 工具结果组织成一条新的 user message。
```

生活类比：

```text
普通聊天：直接把最近聊天记录交给模型。
有工具结果：更像写一份“查询报告”，让模型根据报告回答。
```

所以面试时不能说“所有模式最终都直接把 histMsgs 传给 LLM”。

准确说法是：

```text
chat 模式和 ReAct 无工具结果分支会直接使用 histMsgs。
有工具结果的生成分支可能会重组 prompt，不一定原样传 histMsgs。
```

## 6. 真实例子：在流程中怎么运行

假设短期记忆中已有：

```text
[
  ConversationMessage{role="user", content="我在学短期记忆", timestamp="21:50:00"},
  ConversationMessage{role="assistant", content="短期记忆保存最近几轮对话", timestamp="21:50:04"}
]
```

用户本轮问：

```text
histMsgs 怎么进入 LLM？
```

主流程先写入：

```java
stm.add("user", query);
```

短期记忆变成：

```text
[
  ConversationMessage{role="user", content="我在学短期记忆", timestamp="21:50:00"},
  ConversationMessage{role="assistant", content="短期记忆保存最近几轮对话", timestamp="21:50:04"},
  ConversationMessage{role="user", content="histMsgs 怎么进入 LLM？", timestamp="21:51:10"}
]
```

然后：

```java
List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query);
```

得到：

```text
[
  {"role":"user", "content":"我在学短期记忆"},
  {"role":"assistant", "content":"短期记忆保存最近几轮对话"},
  {"role":"user", "content":"histMsgs 怎么进入 LLM？"}
]
```

普通聊天模式会构造：

```text
sp = memPrefix + "\n\n" + "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。"
```

然后调用：

```java
llm.chat(sp, histMsgs);
```

模型最终看到的是：

```text
system:
  偏好和长期记忆召回结果
  你是一个简洁的AI助手……

messages:
  user: 我在学短期记忆
  assistant: 短期记忆保存最近几轮对话
  user: histMsgs 怎么进入 LLM？
```

## 7. 容易混淆的点

`memPrefix` 和 `histMsgs` 都是上下文，但放的位置不一样。

`memPrefix`：

```text
来源：偏好记忆 + 长期记忆召回结果
位置：system prompt
作用：告诉模型“这个用户有什么稳定信息、长期偏好”
```

`histMsgs`：

```text
来源：ShortTermMemory 最近几轮对话
位置：LLM messages
作用：告诉模型“刚才聊到了哪里”
```

`tool` 模式要特别注意：

```java
toolHandler.run(resp, query, toolset, memPrefix, histMsgs);
```

虽然 `histMsgs` 被传进 `ToolModeHandler.run`，但当前方法最终是：

```java
resp.setAnswer(llm.chat(sp, List.of(Map.of("role", "user", "content", userMsg))));
```

也就是它重新构造了一条 `userMsg`，没有直接把 `histMsgs` 传给最终综合回答的 LLM 调用。

## 8. 面试怎么说

可以这样说：

```text
短期记忆先通过 ChatHistoryAdapter.buildHistory 转成 histMsgs。
在普通 chat 模式下，系统会先用 memPrefix 构造 system prompt，然后调用 llm.chat(sp, histMsgs)，因此最近几轮对话会作为 messages 参数进入模型。
ReAct 没有工具计划时也会这样直接使用 histMsgs。
如果 ReAct 有工具结果，ChatGenerator 会优先基于工具结果重新组织生成 prompt；tool 单步模式当前也会重组 userMsg，所以这两个分支并不是所有情况下都原样把 histMsgs 传给最终 LLM。
```
