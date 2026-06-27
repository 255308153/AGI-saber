# 25-GraphRuntime-groupByRace

## 1. 这个方法解决什么问题

一个拓扑层里的节点并非全部彼此独立——有些节点在同一个竞速组（`raceGroup`）里。`groupByRace` 把同一层的节点 ID 列表按 `raceGroup` 字段分组——有竞速组的节点各自成组，无竞速组的节点合并成一个"普通组"。

## 2. 方法源码

```java
/**
 * 位置：GraphRuntime.java:210-230
 * 输入：层内节点ID列表，如 ["n1", "n2", "n3"]
 * 输出：RaceGroup 列表
 */
private List<RaceGroup> groupByRace(List<String> level) {
    Map<String, List<String>> grouped = new LinkedHashMap<>();
    List<String> noGroup = new ArrayList<>();

    for (String id : level) {
        Node n = graph.getNodes().get(id);
        String rg = n.getRaceGroup();
        if (rg != null && !rg.isEmpty()) {
            grouped.computeIfAbsent(rg, k -> new ArrayList<>()).add(id);  // ① 有竞速组
        } else {
            noGroup.add(id);                                               // ② 无竞速组
        }
    }

    List<RaceGroup> result = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
        result.add(new RaceGroup(e.getKey(), e.getValue()));               // ③ 每个竞速组
    }
    if (!noGroup.isEmpty())
        result.add(new RaceGroup("", noGroup));                            // ④ 普通组合并
    result.sort(Comparator.comparing(g -> g.raceGroup));
    return result;
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 有竞速组**

有竞速组。这是竞速执行的控制逻辑，目标是在多个等价节点中保留第一个成功结果，并尽快停止其他候选。

**② 无竞速组**

无竞速组。这是竞速执行的控制逻辑，目标是在多个等价节点中保留第一个成功结果，并尽快停止其他候选。

**③ 每个竞速组**

每个竞速组。这是竞速执行的控制逻辑，目标是在多个等价节点中保留第一个成功结果，并尽快停止其他候选。

**④ 普通组合并**

普通组合并。没有 raceGroup 的节点被放进同一个普通组，后续会并行执行，但不做 first-success-wins 竞速。


## 3. 分组结果影响执行方式

```text
groupByRace(["n1", "n2", "n3"])
  where n1.raceGroup="", n2.raceGroup="search", n3.raceGroup="search"

返回：
  RaceGroup("", ["n1"])       ← 普通组 → execute 内走 runSingle
  RaceGroup("search", ["n2","n3"]) ← 竞速组 → execute 内走 runRace
```

## 4. 关键设计：无竞速节点合并

第④步——所有 `raceGroup=""` 的节点被合并到一个 `RaceGroup("", ...)`。但外层 `execute` 对这个"空组"不是走 `runRace`，而是走普通并行（每个节点独立 `runSingle`）。空组只是一个容器，不产生竞速行为。

## 5. 位置

```text
execute 内 for each level:
  → groups = groupByRace(level)  ← 你在这里
  → for each group: if 有竞速组 → runRace else → runSingle * N
```

## 6. 用"天气+搜索"的 Level 1 跑一遍

```text
Level 1: ["n2"(search_web, raceGroup:"search"), "n3"(rag_search, raceGroup:"search")]

groupByRace:
  n2: rg="search" → grouped["search"]=["n2"]
  n3: rg="search" → grouped["search"]=["n2","n3"]
  noGroup: []

返回: [RaceGroup("search", ["n2","n3"])]
  → execute 内：有竞速组 + 启用竞速 + >1节点 → runRace
```

## 7. 常见误解

**误解一："空 raceGroup 的节点各自走独立 runRace"**

不对。空组走的是普通并行——每个节点独立 `runSingle`，等所有完成。不是竞速的 first-success-wins。

**误解二："不同竞速组之间也会竞速"**

不会。每个竞速组独立跑 `runRace`。两个不同 `raceGroup` 之间互不影响。
