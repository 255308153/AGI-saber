# 05-长期记忆召回-score分类过滤图扩展

## 1. 一句话结论

长期记忆召回就是：

```text
用当前 query 找出最相关的 MemoryItem，拼进 memPrefix 给 LLM 使用。
```

---

## 2. 基础召回流程

源码：

```java
List<Double> queryEmb = llm.embed(query);
List<MemoryItem> recalled = ltm.recall(query, topK, queryEmb);
```

流程：

```text
1. 对 query 做 embedding。
2. 遍历长期记忆 MemoryItem。
3. 计算 queryEmbedding 和 item.embedding 的 cosine 相似度 sim。
4. 计算 score。
5. 超过阈值的候选按 score 排序。
6. 取 topK。
```

---

## 3. score 怎么算

公式：

```text
score = sim * 0.7 + importance * 0.3
```

例子：

```text
sim = 0.8
importance = 0.7

score = 0.8 * 0.7 + 0.7 * 0.3
      = 0.56 + 0.21
      = 0.77
```

区别：

```text
sim：这条记忆和当前问题像不像。
importance：这条记忆本身重不重要。
score：最终召回排序分。
```

---

## 4. category 和 tags

category 是固定大类：

```text
identity
preference
tool_failure
policy
general
```

tags 是自由标签：

```text
["Java", "面试"]
["城市", "天气"]
["回答风格"]
```

区别：

```text
category 用来粗过滤。
tags 用来辅助细分。
```

---

## 5. 分类过滤不能太细

当前 `requiredTags` 是 AND 关系。

例子：

```text
requiredTags = ["图记忆", "天气", "上海"]
```

意思是：

```text
一条记忆必须同时包含这三个 tag 才能通过。
```

问题：

```text
"用户正在学习图记忆" 没有天气 tag，会被过滤掉。
"用户默认城市是上海" 没有图记忆 tag，也会被过滤掉。
```

正确做法：

```text
category 做粗过滤。
tags 谨慎使用。
多意图 query 先拆成子 query 分别召回。
最后用 embedding + score 排序。
```

---

## 6. 图扩展和长期召回关系

图记忆召回不是另起炉灶。

流程：

```text
1. ltm.recall 先召回 seed。
2. GraphMemory 拿 seedIds 去 Neo4j 扩展邻居。
3. 把邻居 MemoryItem 加入结果。
4. 合并排序取 topK。
```

面试表达：

```text
图记忆是在长期记忆 seed 基础上做邻居扩展，用图关系补充向量召回可能漏掉的上下文。
```

---

## 7. 最容易说错

```text
score 不是 importance。
importance 写入时确定，score 召回时计算。
category 是固定五类，tags 不是固定枚举。
过滤不是越细越好。
图扩展依赖 seed 召回，不是替代长期召回。
```

