# 20-Node

## 1. 这个方法解决什么问题

`Node` 是任务图中"一步任务"的数据载体。它可以是一个工具调用（`type=TOOL`），也可以是一个子 Agent 任务（`type=SUB_AGENT`）。所有 Planner 的产出、TaskGraph 的存储、GraphRuntime 的调度，都围绕 `Node` 展开。

## 2. 完整字段和源码

```java
/**
 * 位置：domain/graph/Node.java (96 行)
 */
public class Node {
    private String id;                          // ① 唯一标识，如 "n1"
    private NodeType type = NodeType.TOOL;      // ② TOOL 或 SUB_AGENT
    private String name;                        // ③ 显示名（Planner 的 reason）
    private String toolName;                    // ④ 工具名（TOOL 节点必填）
    private String agentName = "";              // ⑤ 子Agent名（SUB_AGENT 节点必填）
    private String goal = "";                   // ⑥ 子Agent 目标描述
    private Map<String, String> params = new LinkedHashMap<>(); // ⑦ 执行参数
    private List<String> dependsOn = new ArrayList<>();  // ⑧ 入边：依赖的节点ID
    private String raceGroup = "";              // ⑨ 竞速组名
    private NodeStatus status = NodeStatus.PENDING; // ⑩ 执行状态
    private String result = "";                 // ⑪ 执行结果
    private String error = "";                  // ⑫ 错误信息
    private int retryCount;                     // ⑬ 重试次数
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 唯一标识，如 "n1"**

唯一标识，如 "n1"。这是 Node 的运行时字段，Planner 生成它，TaskGraph 组织它，GraphRuntime 执行时不断读取和更新它。

**② TOOL 或 SUB_AGENT**

TOOL 或 SUB_AGENT。这是 Node 的运行时字段，Planner 生成它，TaskGraph 组织它，GraphRuntime 执行时不断读取和更新它。

**③ 显示名（Planner 的 reason）**

显示名（Planner 的 reason）。这是 Node 的运行时字段，Planner 生成它，TaskGraph 组织它，GraphRuntime 执行时不断读取和更新它。

**④ 工具名（TOOL 节点必填）**

工具名（TOOL 节点必填）。GraphRuntime 会用它从 `tools` Map 里取出对应 Tool。

**⑤ 子Agent名（SUB_AGENT 节点必填）**

子Agent名（SUB_AGENT 节点必填）。这是 Node 的运行时字段，Planner 生成它，TaskGraph 组织它，GraphRuntime 执行时不断读取和更新它。

**⑥ 子Agent 目标描述**

子Agent 目标描述。这是工具说明文本，用来告诉调用方这个工具能做什么；它影响规划和展示，不直接参与执行。

**⑦ 执行参数**

执行参数。Planner 或规则规划写入这里，`GraphRuntime.invoke` 会把它转换成工具 `apply` 需要的参数 Map。

**⑧ 入边：依赖的节点ID**

入边：依赖的节点ID。这是拓扑排序的关键状态变化，用来判断哪些节点可以并行执行、哪些节点必须等待上游完成。

**⑨ 竞速组名**

竞速组名。这是竞速执行的控制逻辑，目标是在多个等价节点中保留第一个成功结果，并尽快停止其他候选。

**⑩ 执行状态**

执行状态。节点运行时会在 PENDING、RUNNING、DONE、FAILED、SKIPPED、CANCELLED 之间切换。

**⑪ 执行结果**

执行结果。工具或子 Agent 成功后把原始字符串写到这里，最后 `successfulResults()` 会收集它。

**⑫ 错误信息**

错误信息。这是 Node 的运行时字段，Planner 生成它，TaskGraph 组织它，GraphRuntime 执行时不断读取和更新它。

**⑬ 重试次数**

重试次数。这是 Node 的运行时字段，Planner 生成它，TaskGraph 组织它，GraphRuntime 执行时不断读取和更新它。


## 3. 字段详细解释

| 字段 | 类型 | 谁写 | 何时写 | 谁读 |
|---|---|---|---|---|
| `id` | `String` | Planner | 规划时 | TaskGraph（key）、GraphRuntime（寻址） |
| `type` | `NodeType` | Planner | 规划时 | GraphRuntime.invoke（分发） |
| `name` | `String` | Planner | 规划时 | SSE 事件、前端展示 |
| `toolName` | `String` | Planner | 规划时 | `ts.get(toolName)`、invoke |
| `agentName` | `String` | Planner | 规划时 | `subAgents.get(agentName)` |
| `goal` | `String` | Planner | 规划时 | `SubAgentTask` 构造 |
| `params` | `Map<String,String>` | Planner | 规划时 | `t.getExecute().apply(params)` |
| `dependsOn` | `List<String>` | Planner | 规划时 | TaskGraph 构造（建入度） |
| `raceGroup` | `String` | Planner | 规划时 | `groupByRace`（分组） |
| `status` | `NodeStatus` | GraphRuntime | 执行时 | `successfulResults`（筛选) |
| `result` | `String` | GraphRuntime | 执行完 | `successfulResults`、upstream |
| `error` | `String` | GraphRuntime | 失败时 | 错误传播 |
| `retryCount` | `int` | GraphRuntime | 重试时 | SSE 事件 |

## 4. 工具节点 vs 子 Agent 节点

| | TOOL 节点 | SUB_AGENT 节点 |
|---|---|---|
| `type` | `TOOL` | `SUB_AGENT` |
| `toolName` | 必填 | 空 |
| `agentName` | 空 | 必填 |
| `goal` | 空 | 必填 |
| `executorName()` | 返回 `toolName` | 返回 `agentName` |
| invoke 方式 | `t.getExecute().apply(params)` | `sa.run(task, cancelled)` |

## 5. 位置

```text
Planner.planGraph / rulePlanNodes
  → new Node(id, TOOL, name, toolName, params, dependsOn, raceGroup)
  → Node.subAgent(id, name, agentName, goal, dependsOn, raceGroup)
  → List<Node> 进入 ReActLoop.runStream 的 Step 2～3
```

## 6. 用"查询上海天气，搜索出行建议"跑一遍

```text
Planner 产生的 Node 实例：

n1: {
  id="n1", type=TOOL, name="查询上海天气",
  toolName="get_weather", params={city:"上海"},
  dependsOn=[], raceGroup="", status=PENDING
}

n2: {
  id="n2", type=TOOL, name="搜索出行建议",
  toolName="search_web", params={query:"小雨出门建议"},
  dependsOn=["n1"], raceGroup="search", status=PENDING
}

n3: {
  id="n3", type=TOOL, name="检索个人知识库",
  toolName="rag_search", params={query:"小雨出门建议"},
  dependsOn=["n1"], raceGroup="search", status=PENDING
}
```

## 7. 常见误解

**误解一："Node 的 params 和 ToolCallResult 的 params 是一回事"**

类型不同：`Node.params` 是 `Map<String,String>`，`ToolCallResult.params` 是 `Map<String,Object>`。但语义相同——都是"这次调用的参数值"。

**误解二："status 是 Planner 设置的"**

不是。Planner 只设 `id/type/name/toolName/params/dependsOn/raceGroup`。`status` 初始是 `PENDING`（默认值），由 `TaskGraph` 构造方法重置确认，由 `GraphRuntime` 在执行过程中改写。

**误解三："dependsOn 和 raceGroup 互斥"**

不互斥。一个节点可以同时有依赖和竞速。但在当前规则规划中，所有有竞速组的节点（search_web、rag_search）的 dependsOn 都是空的。
