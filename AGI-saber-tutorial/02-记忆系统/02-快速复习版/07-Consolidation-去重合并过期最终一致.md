# 07-Consolidation-去重合并过期最终一致

## 1. 一句话结论

consolidation 是长期记忆后台整理：

```text
旧记忆降权
重复记忆删除
相似记忆合并
过期低价值记忆清理
同步 PostgreSQL 和 Neo4j
```

它不是召回。

---

## 2. 触发条件

源码逻辑：

```java
return consolidationCfg != null
        && consolidationCfg.getTriggerInterval() > 0
        && storeCount >= consolidationCfg.getTriggerInterval();
```

解释：

```text
consolidationCfg 不为空：开启了整理配置。
triggerInterval > 0：配置了多少次新增后整理一次。
storeCount >= triggerInterval：新增记忆数量达到触发线。
```

---

## 3. 三个阶段

### 3.1 Decay

```text
按时间衰减 importance。
越久没用的记忆，重要性越低。
```

### 3.2 Dedup + Merge

```text
两两比较记忆相似度。

sim >= dedupThreshold：
认为重复，删除一条。

sim >= similarityThreshold 但没到 dedupThreshold：
认为相关，合并成一条。
```

### 3.3 Expire

```text
days > ttlDays 且 importance < minImportance：
过期删除。
```

---

## 4. mergeItems 怎么合并

规则：

```text
保留 importance 更高那条的 ID。
content 互不包含时，用分号拼接。
importance 取更高值。
embedding 用 importance 加权平均。
```

加权平均：

```text
merged[i] = (a[i] * wA + b[i] * wB) / (wA + wB)
```

术语：

```text
Weighted Average
Weighted Vector Average
```

---

## 5. ConsolidationResult 是什么

它是同步清单：

```java
public static class ConsolidationResult {
    public int deduped;
    public int merged;
    public int expired;
    public List<Integer> deleteFromDB;
    public List<MemoryItem> updateInDB;
}
```

解释：

```text
deleteFromDB：哪些 ID 要从 PostgreSQL 删除。
updateInDB：哪些合并后的 MemoryItem 要更新回 PostgreSQL。
```

---

## 6. 内存、DB、图怎么保持一致

当前实现是最终一致，不是强一致。

流程：

```text
1. ltm.consolidate() 先整理内存 items。
2. 返回 ConsolidationResult。
3. syncConsolidationToDB(result) 删除/更新 PostgreSQL。
4. graphAwareConsolidate() 根据 deleteFromDB 异步删除 Neo4j 节点。
```

三边靠统一 ID 对齐：

```text
MemoryItem.id
PostgreSQL long_term_memory.id
Neo4j Memory.mem_id
```

---

## 7. 图模式边界

当前 Java 版：

```text
graphAwareConsolidate() 先 ltm.consolidate() 改内存，
再查 Neo4j 中心度保护。
```

所以：

```text
图保护主要保护 PostgreSQL 和 Neo4j 不删。
不能严格保证被保护节点仍然留在当前 JVM 内存 items 里。
```

改进：

```text
用 plan/apply 两阶段。
先计算删除计划和图保护。
再统一修改内存、DB、Neo4j。
```

---

## 8. 面试怎么说

```text
consolidation 是后台记忆整理。它先对 importance 做时间衰减，再两两比较记忆，相似度超过 dedupThreshold 的去重，超过 similarityThreshold 的合并，最后删除超过 ttlDays 且 importance 低于 minImportance 的记忆。整理后通过 ConsolidationResult 同步 PostgreSQL 和 Neo4j。当前实现是最终一致，不是强一致，如果要增强一致性，可以改成 plan/apply 两阶段并加入补偿重试。
```

