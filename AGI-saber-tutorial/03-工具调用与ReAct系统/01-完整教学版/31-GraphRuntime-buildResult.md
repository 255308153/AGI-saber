# 31-GraphRuntime-buildResult

## 1. 这个方法解决什么问题

图执行完成，每个节点的状态和结果散落在 `TaskGraph` 的各处。`buildResult` 把它们整理成统一的 `GraphResult`——包含 observations（供 LLM 总结）和 nodeResults（供前端查看每个节点的详情）。

## 2. 方法源码

```java
/**
 * 位置：GraphRuntime.java:488-496
 */
private GraphResult buildResult() {
    GraphResult r = new GraphResult();
    r.observations = graph.successfulResults();                   // ① 第30章 — LLM 用的
    for (Map.Entry<String, Node> e : graph.getNodes().entrySet()) {
        Node n = e.getValue();
        r.nodeResults.put(e.getKey(),                             // ② 每个节点一个 NodeResult
            new NodeResult(n.getStatus(), n.getResult(), n.getError()));
    }
    return r;
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 第30章 — LLM 用的**

第30章 — LLM 用的。`successfulResults()` 会过滤出成功节点的结果，作为最终总结阶段的 observations。

**② 每个节点一个 NodeResult**

每个节点一个 NodeResult。这是结果整理阶段的过滤或记录逻辑，决定哪些内容给 LLM 总结，哪些内容只给前端调试查看。


## 3. 返回值结构

```java
class GraphResult {
    List<String> observations;              // ["[get_weather] 上海：小雨 20°C", ...]
    Map<String, NodeResult> nodeResults;    // {"n1"→{status:DONE, result:"上海：小雨 20°C", error:""}, ...}
    boolean interrupted;                    // 是否被中断
}
```

## 4. observations 和 nodeResults 的分工

```text
observations → ChatGenerator.generate → 用户看到的一句话答案
nodeResults  → 前端 → 任务执行的详细视图（每个节点什么状态、什么结果）
```

`observations` 是汇总后的精简结果，`nodeResults` 是完整的执行记录。

## 5. 位置

```text
GraphRuntime.execute
  ├── for each level: runSingle/runRace
  ├── pool.shutdown
  └── buildResult()  ← 你在这里
        └── GraphResult → ReActLoop.runStream
              ├── gr.observations → generator.generate
              └── gr.nodeResults  → (可选使用)
```

## 6. 用执行完的图跑一遍

```text
执行完的 graph:
  n1: DONE,   result="上海：小雨 20°C",           error=""
  n2: SKIPPED, result="",                          error=""
  n3: DONE,   result="雨天建议：带好雨具...",       error=""
  n4: FAILED,  result="",                          error="连接超时"

buildResult:
  observations = [
    "[get_weather] 上海：小雨 20°C",
    "[rag_search] 雨天建议：带好雨具..."
  ]  ← n2 SKIPPED 和 n4 FAILED 不在其中

  nodeResults = {
    "n1": NodeResult(DONE,   "上海：小雨 20°C", ""),
    "n2": NodeResult(SKIPPED, "",                ""),
    "n3": NodeResult(DONE,   "雨天建议：...",     ""),
    "n4": NodeResult(FAILED,  "",                "连接超时")
  }
```

## 7. 常见误解

**误解一："buildResult 也包含中断图的结果"**

中断图的 `buildResult` 由 `interrupted()` 方法在调 `buildResult` 之前标记了 `r.interrupted=true`。所以 `observations` 包含已完成节点的结果，同时 `interrupted=true` 告诉调用方"这不是完整的"。

**误解二："nodeResults 包含 observations 过滤掉的 FAILED 节点"**

是的。`nodeResults` 是完整的，包含所有节点的最终状态。`observations` 是精简的，只含成功节点。前端可以用 `nodeResults` 展示"哪些成功了、哪些失败了、哪些被跳过了"。
