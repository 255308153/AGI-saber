# 30-TaskGraph-successfulResults

## 1. 这个方法解决什么问题

图执行完了——有些节点 DONE，有些 FAILED，有些 SKIPPED。哪些节点的结果应该交给 LLM 总结？`successfulResults` 负责筛选：**只取状态为 DONE 且结果非空的节点**，失败和被跳过的排除在外。

## 2. 方法源码

```java
/**
 * 位置：TaskGraph.java:149-157
 */
public List<String> successfulResults() {
    List<String> results = new ArrayList<>();
    for (Node n : nodes.values()) {
        if (n.getStatus() == NodeStatus.DONE              // ① 必须成功
                && n.getResult() != null                  // ② 结果不能为 null
                && !n.getResult().isEmpty()) {             // ③ 结果不能为空字符串
            results.add("[" + n.executorName() + "] " + n.getResult());  // ④ 格式：[工具名] 结果
        }
    }
    return results;
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 必须成功**

必须成功。这里是保护条件，目的是避免后面的执行逻辑拿到空对象、错误工具或不满足条件的数据。

**② 结果不能为 null**

结果不能为 null。这是结果整理阶段的过滤或记录逻辑，决定哪些内容给 LLM 总结，哪些内容只给前端调试查看。

**③ 结果不能为空字符串**

结果不能为空字符串。这里是保护条件，目的是避免后面的执行逻辑拿到空对象、错误工具或不满足条件的数据。

**④ 格式：[工具名] 结果**

格式：[工具名] 结果。前缀告诉 LLM 每条 observation 来自哪个执行体，避免多个工具结果混在一起。


## 3. 三种被排除的情况

```text
FAILED 节点 → 不包含 → LLM 不会收到"执行失败"的信息
SKIPPED 节点 → 不包含 → 竞速失败的 search_web 结果不会干扰 LLM
DONE 但 result 为 null 或 "" → 不包含 → 防御性过滤
```

## 4. 结果格式

```text
"[get_weather] 上海：小雨 20°C"
"[search_web] 小雨天出行建议：带好雨具..."
```

前缀 `[toolName]` 告诉 LLM 这个结果来自哪个工具，方便 LLM 判断结果的来源和可靠性。

## 5. 位置

```text
GraphRuntime.buildResult
  └── graph.successfulResults()  ← 你在这里
        └── 产出 observations → ChatGenerator.generate(query, observations, ...)
```

## 6. 用执行完的图跑一遍

```text
执行完：
  n1: status=DONE,   result="上海：小雨 20°C"
  n2: status=SKIPPED, result=""            (竞速失败)
  n3: status=DONE,   result="雨天建议：带好雨具..."

successfulResults:
  n1: DONE + result非空 → "[get_weather] 上海：小雨 20°C"
  n2: SKIPPED → 跳过
  n3: DONE + result非空 → "[rag_search] 雨天建议：带好雨具..."

→ observations = [
    "[get_weather] 上海：小雨 20°C",
    "[rag_search] 雨天建议：带好雨具..."
  ]
```

## 7. 常见误解

**误解一："FAILED 节点的错误信息会包含在 observations 中"**

不会。`result` 字段可能为空或包含错误信息，但 `status=FAILED` 直接跳过了——`successfulResults` 把 FAILED 和 SKIPPED 同等对待。

**误解二："observations 的顺序是按节点 ID 排序"**

是按 `nodes.values()` 的迭代顺序——即 `LinkedHashMap` 的插入顺序。由于构造时按 `List<Node> all` 的顺序插入，这个顺序反映了 Planner 规划时的顺序。

**误解三："executorName() 对 TOOL 节点和 SUB_AGENT 节点返回不同的值"**

是的。TOOL 返回 `toolName`（如 `"get_weather"`），SUB_AGENT 返回 `agentName`（如 `"research_agent"`）。前缀随节点类型变化。
