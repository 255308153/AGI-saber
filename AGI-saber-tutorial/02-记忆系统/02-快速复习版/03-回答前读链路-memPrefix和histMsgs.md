# 03-回答前读链路-memPrefix和histMsgs

## 1. 一句话结论

回答前主要准备两个东西：

```text
memPrefix：偏好 + 相关长期/图记忆，放 system prompt。
histMsgs：最近聊天记录，放 LLM messages。
```

---

## 2. memPrefix 是什么

来源：

```text
PreferenceMemory.buildContext()
LongTermMemory.recall(...)
GraphMemory.recall(...)
```

例子：

```text
【用户偏好】
姓名: 小李
回答偏好: 喜欢 Java 逐行解释

【相关记忆】
用户正在学习 AGI-saber 记忆系统
用户默认查询城市是上海
```

作用：

```text
告诉 LLM：这个用户有哪些稳定信息，以及这次问题相关的长期事实。
```

---

## 3. histMsgs 是什么

来源：

```text
ChatHistoryAdapter.buildHistory(stm, query)
```

例子：

```text
[
  {"role": "user", "content": "上次讲到长期记忆召回"},
  {"role": "assistant", "content": "长期记忆召回是..."},
  {"role": "user", "content": "图记忆怎么召回？"}
]
```

作用：

```text
告诉 LLM 最近聊天上下文。
```

---

## 4. 召回为什么同步

源码：

```java
String memPrefix = buildMemorySystemPrefixWithCtx(query);
```

里面会做：

```java
List<Double> queryEmb = llm.embed(query);
List<MemoryItem> recalled = graphMem != null
        ? graphMem.recall(query, topK, queryEmb)
        : ltm.recall(query, topK, queryEmb);
```

结论：

```text
召回是回答前同步执行。
```

原因：

```text
召回结果直接进入 system prompt。
如果召回异步，LLM 回答时可能看不到相关记忆，回答质量会下降。
```

---

## 5. memPrefix 和 histMsgs 区别

| 对比项 | memPrefix | histMsgs |
|---|---|---|
| 内容 | 偏好 + 相关长期/图记忆 | 最近聊天原文 |
| 来源 | PreferenceMemory + LongTermMemory/GraphMemory | ShortTermMemory |
| 放哪里 | system prompt | messages |
| 作用 | 提供稳定背景和相关事实 | 提供当前对话上下文 |

---

## 6. 面试怎么说

```text
回答前系统会构造两类上下文。memPrefix 由用户偏好和长期/图记忆召回结果组成，会进入 system prompt；histMsgs 来自 ShortTermMemory，会进入 LLM messages。长期/图召回是同步执行，因为它直接决定当前回答能不能看到相关记忆。
```

