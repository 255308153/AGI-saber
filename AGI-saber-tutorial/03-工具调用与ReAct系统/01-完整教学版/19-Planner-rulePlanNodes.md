# 19-Planner-rulePlanNodes

## 1. 这个方法解决什么问题

当没有真实 LLM（mock 模式）或 LLM 规划失败时，`rulePlanNodes` 作为降级方案——用关键词匹配生成节点列表。它和 `planGraph` 产出同样的 `List<Node>`，但逻辑是确定性的规则，不是 LLM 推理。

## 2. 方法源码关键路径

```java
/**
 * 位置：Planner.java:348-426
 */
private List<Node> rulePlanNodes(String query, Map<String, Tool> ts) {
    String q = query.toLowerCase();                    // ① 统一小写，后面用关键词匹配
    List<Node> nodes = new ArrayList<>();               // ② 准备输出节点列表
    int[] counter = {0};                                // ③ 节点编号计数器
    Supplier<String> nextID = () -> "n" + (++counter[0]); // ④ 生成 n1/n2/n3

    // 分支A：子Agent链
    if (needsSubAgentPlan(q)) {
        // research → writer → review → (可选 doc_agent)  // ⑤ 复杂研究任务走子Agent链
        return nodes;                                   // ⑥ 子Agent规划完成后直接返回
    }

    // 分支B：时间 "几点"/"时间"
    if (ts has get_time && q has 时间关键词) { nodes.add(timeNode); } // ⑦ 加时间节点

    // 分支C：天气 "天气"
    if (ts has get_weather && q has 天气) { nodes.add(weatherNode); } // ⑧ 加天气节点

    // 分支D：搜索 + raceGroup="search"
    if (ts has search_web && q has 搜索关键词) {
        nodes.add(searchNode with raceGroup="search");  // ⑨ 加互联网搜索竞速节点
    }

    // 分支E：exec_command
    if (ts has exec_command && q has 执行/运行/命令...) { nodes.add(execNode); } // ⑩ 加命令节点

    // 分支F：rag_search + raceGroup="search" (与D竞速)
    if (ts has rag_search) {
        nodes.add(ragNode with raceGroup="search");     // ⑪ 加知识库搜索竞速节点
    }

    // 分支G：剩余MCP/自定义工具（每个独立并行，params填query）
    for (未匹配的工具) { nodes.add(并行节点); }          // ⑫ 兜底加入其他工具

    return nodes;                                       // ⑬ 返回规则规划结果
}
```

### 2.1 逐行解释

**① 统一小写，后面用关键词匹配**

规则规划不调用 LLM，所以只能靠字符串匹配。先把 query 转小写，避免英文关键词大小写影响判断。

**② 准备输出节点列表**

`nodes` 是最终返回的 `List<Node>`。后面的每个分支只要命中，就往这个列表里添加一个任务节点。

**③ 节点编号计数器**

规则规划需要给每个节点一个唯一 id，例如 `n1`、`n2`。这里用数组是为了让 lambda 里能修改计数值。

**④ 生成 n1/n2/n3**

每调用一次 `nextID.get()`，计数器加 1，并返回新的节点 id。这个 id 后面会被 `dependsOn` 引用，也会被 `TaskGraph` 当作 Map key。

**⑤ 复杂研究任务走子Agent链**

如果 query 命中研究、报告、文档等关键词，就不再按普通工具规划，而是生成 `research_agent → writer_agent → review_agent` 这样的子 Agent 链。

**⑥ 子Agent规划完成后直接返回**

子 Agent 链是完整规划，不需要再继续添加时间、天气、搜索等节点，所以直接返回。

**⑦ 加时间节点**

如果工具集里有 `get_time`，并且 query 命中 `"时间"` 或 `"几点"`，就添加一个时间工具节点。这个节点通常没有依赖，可以在图的第一层执行。

**⑧ 加天气节点**

如果有 `get_weather` 且 query 包含 `"天气"`，就添加天气节点。代码会尝试从 query 里提取城市，放入节点参数。这个参数后面由 `GraphRuntime.invoke` 转成 `Map<String,Object>` 后传给工具。

**⑨ 加互联网搜索竞速节点**

搜索节点的 `raceGroup` 是 `"search"`。这表示它和同组的其他搜索节点功能相近，后面 `GraphRuntime.runRace` 会让它们竞速，谁先成功用谁。

**⑩ 加命令节点**

如果用户明确说执行、运行、命令，并且工具集中有 `exec_command`，规则规划会加入命令执行节点。这个节点风险更高，所以必须依赖工具是否被注册进 `ts`。

**⑪ 加知识库搜索竞速节点**

`rag_search` 也放进 `"search"` 竞速组。这样互联网搜索和知识库搜索可以并发，最终只把胜出的结果交给总结阶段。

**⑫ 兜底加入其他工具**

没有被前面规则覆盖的 MCP 或自定义工具，会用 query 填参数并加入节点。这是兜底策略，准确性不如 LLM 规划。

**⑬ 返回规则规划结果**

返回的 `nodes` 会进入 `new TaskGraph(nodes)`，然后由 `GraphRuntime.execute` 按拓扑层执行。

## 3. 关键设计：depends_on 和 race_group

```text
时间节点：dependsOn=[], raceGroup=""       ← 独立
天气节点：dependsOn=[], raceGroup=""       ← 独立
搜索节点：dependsOn=[], raceGroup="search" ← 竞速组
RAG节点： dependsOn=[], raceGroup="search" ← 同一竞速组

以 search_web 和 rag_search 为例 — 两者都搜索，
但一个搜互联网一个搜知识库。同 raceGroup="search"，
GraphRuntime 会并发执行两者，谁先返回用谁的。
```

## 4. 这章核心：依赖和竞速的表达

`dependsOn` 描述数据依赖（B 需要 A 的输出），在执行时体现为**拓扑排序**。`raceGroup` 描述功能等价（谁先成功就够），在执行时体现为**竞速 first-success-wins**。

`rulePlanNodes` 产出的节点**全都没有 `dependsOn`**——都是独立并行或竞速。这是规则规划的限制：它缺少对"B 需要 A 的输出"的判断能力。

## 5. 位置

```text
planGraph
  └── LLM失败 / mock模式 → rulePlanNodes  ← 你在这里
```

## 6. 用"上海天气怎么样？"跑一遍

```text
q = "上海天气怎么样？"

needsSubAgentPlan? "研究/调研/总结/报告/文档/方案/分析"→ 都不包含 → false

分支B: "时间"/"几点" → 无 → 跳过
分支C: "天气" ✓ + get_weather存在 ✓
  → newNode(n1, TOOL, "查询上海天气", "get_weather", {city:"上海"}, [], "")
分支D: "搜索"/"查询"... → 无 → 跳过
分支F: rag_search存在 → newNode(n2, TOOL, "检索个人知识库", "rag_search", {query:...}, [], "search")

返回 [n1, n2]
```

## 7. 常见误解

**误解一："rulePlanNodes 产出的节点和 LLM 规划的节点不一样"**

类型完全一样——都是 `Node` 对象。区别在于质量：rule 的 `dependsOn` 永远是空，`raceGroup` 只在搜索/RAG 间有。

**误解二："分支 G 的 MCP 工具按 query 填第一个必填参数就够了"**

这只是兜底——如果 MCP 工具有 3 个必填参数，只填了第一个，另外两个为空。这是规则规划的先天局限。
