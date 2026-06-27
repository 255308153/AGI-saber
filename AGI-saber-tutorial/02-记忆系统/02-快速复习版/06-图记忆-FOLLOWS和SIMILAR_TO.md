# 06-图记忆-FOLLOWS和SIMILAR_TO

## 1. 一句话结论

图记忆是在长期记忆上加一层 Neo4j 关系网络：

```text
MemoryItem 负责内容和 embedding。
Neo4j 负责节点和边。
```

---

## 2. 图节点长什么样

Neo4j 节点：

```text
(:Memory {
  mem_id: 101,
  content: "用户正在学习图记忆召回链路",
  importance: 0.8
})
```

对应关系：

```text
MemoryItem.id = PostgreSQL id = Neo4j Memory.mem_id
```

---

## 3. FOLLOWS 边

创建时机：

```text
新增图记忆时，如果上一条记忆 prevId 存在，就建立 prevId -> newId。
```

代码含义：

```text
kg.addMemoryEdge(prevId, newId, "FOLLOWS", 1.0)
```

意思：

```text
上一条记忆之后，写入了这一条新记忆。
```

注意：

```text
FOLLOWS 是顺序边，不是因果边。
```

---

## 4. SIMILAR_TO 边

创建时机：

```text
新增记忆后，把它和最近一批旧记忆做 embedding 相似度比较。
如果 sim >= simThreshold，就建立 SIMILAR_TO。
```

例子：

```text
Memory A = "用户正在学习长期记忆召回"
Memory B = "用户正在学习图记忆召回"

sim = 0.82
simThreshold = 0.7

创建：
(A)-[:SIMILAR_TO {weight: 0.82}]->(B)
```

---

## 5. 图记忆怎么召回

流程：

```text
1. 长期记忆先召回 seed。
2. seedIds = [101, 102]
3. KGStore.expandMemoryNeighbors(seedIds, 1)
4. Neo4j 返回一跳邻居 ID。
5. GraphMemory 去 LongTermMemory.items 里找这些 ID。
6. 找到就加入结果。
```

注意：

```text
Neo4j 只存节点关系。
真正返回给 LLM 的 content 仍然来自 LongTermMemory.items。
```

---

## 6. BELONGS_TO 怎么看

准确说法：

```text
代码注释和 KGStore 支持 BELONGS_TO 这种边类型，
但当前 GraphMemory.storeClassified 自动创建的是 FOLLOWS 和 SIMILAR_TO。
BELONGS_TO 当前不是自动写入主路径。
```

---

## 7. 面试怎么说

```text
图记忆不是独立替代长期记忆，而是在长期记忆基础上建立 Neo4j 图层。新增记忆时会创建 Memory 节点，如果有上一条记忆就建立 FOLLOWS 顺序边；如果新旧记忆 embedding 相似度超过 simThreshold，就建立 SIMILAR_TO 相似边。召回时先用长期记忆找到 seed，再沿图扩展一跳邻居，把邻居对应的 MemoryItem 合并进结果。
```

