# 17-ReActLoop-runStream

## 1. 这个方法解决什么问题

当 `decideMode` 返回 `"react"` 后，`ReActLoop.runStream` 接管请求。它的职责和 `ToolModeHandler.run` 一样——编排一个完整的多步工具调用流程，但流程更复杂：不是"决策 → 执行 → 总结"三步，而是"规划 → 建图 → 执行图 → 检查中断 → 综合生成"五步。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ReActLoop.java:78-149
 * 参数：resp / query / ts / memPrefix / histMsgs / cancelled / onEvent
 */
public void runStream(ChatResponse resp, String query, Map<String, Tool> ts,
                      String memPrefix, List<Map<String, String>> histMsgs,
                      AtomicBoolean cancelled, Consumer<StreamEvent> onEvent) {
    List<ReActStep> reactSteps = new ArrayList<>();

    // ── Step 1: Planner 输出带依赖的图节点 ──
    List<Node> planNodes = planner.planGraph(query, ts, memPrefix);   // ① 规划出工具/子Agent节点

    if (planNodes == null || planNodes.isEmpty()) {                   // ② 规划为空 → 降级纯 chat
        String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
                "你是一个简洁的AI助手...");
        String answer = llm.chat(sp, histMsgs);
        reactSteps.add(new ReActStep(ReActStep.THOUGHT, "分析后无需调用工具，直接回答"));
        reactSteps.add(new ReActStep(ReActStep.FINAL_ANSWER, answer));
        resp.setAnswer(answer);
        resp.setSteps(reactSteps);
        return;
    }

    // ── Step 2: 构建 TaskGraph（校验失败则降级为全并行）──
    TaskGraph tg;
    try {
        tg = new TaskGraph(planNodes);                               // ③ 节点列表变成可调度图
        tg.validate();                                               // ④ 校验依赖、环、节点字段
    } catch (Exception e) {
        log.warn("TaskGraph 校验失败 ({}), 降级为全并行执行", e.getMessage());
        for (Node n : planNodes) n.setDependsOn(new ArrayList<>());   // ⑤ 清除所有依赖
        tg = new TaskGraph(planNodes);                               // ⑥ 重建：纯并行图
    }

    // ── Step 3: 保存旧 TaskState 快照，便于前端/调试接口观察进度 ──
    TaskState currentTask = new TaskState();                         // ⑦ 创建任务状态
    currentTask.setTaskId("task-" + System.nanoTime());
    currentTask.setQuery(query);
    currentTask.setStatus("running");
    currentTask.setPhase("executing");
    currentTask.setSteps(graphToTaskSteps(tg));
    snapshots.clear();
    snapshots.save(currentTask);

    // ── Step 4: GraphRuntime 并行/竞速执行 ──
    GraphRuntime rt = new GraphRuntime(tg, cfg,
            GraphRuntime.Config.from(cfg), ts, subAgents, query,
            cancelled, onEvent);
    GraphRuntime.GraphResult gr = rt.execute();                      // ⑧ 真正执行图节点
    syncGraphIntoTaskSteps(tg, currentTask);
    snapshots.save(currentTask);
    reactSteps.addAll(graphResultToReActSteps(tg));

    // ── Step 5: 中断检查 ──
    if (cancelled.get() || gr.interrupted) {                         // ⑨ 用户取消或图内中断
        currentTask.setPhase("interrupted");
        currentTask.setStatus("interrupted");
        String msg = GraphRuntime.buildInterruptMessage(tg);
        resp.setAnswer("[已中断] " + msg);
        resp.setSteps(reactSteps);
        resp.setTask(currentTask);
        resp.setInterrupted(true);
        return;
    }

    // ── Step 6: Generator LLM 综合所有观察 ──
    currentTask.setPhase("generating");
    String answer = generator.generate(query, gr.observations,       // ⑩ 综合 observations
            memPrefix, histMsgs);
    reactSteps.add(new ReActStep(ReActStep.FINAL_ANSWER, answer));
    currentTask.setResult(answer);
    currentTask.setStatus("completed");
    currentTask.setPhase("done");
    resp.setAnswer(answer);
    resp.setSteps(reactSteps);
    resp.setTask(currentTask);
}
```
### 2.1 逐行解释

下面按照源码里的编号，把 `runStream` 的大流程讲清楚：它怎么把一次用户请求变成一个多节点工具执行图，再怎么把多个工具结果综合成最终回答。

**① `planner.planGraph(query, ts, memPrefix)`：先把用户问题规划成图节点**

`Planner` 会根据用户输入、当前可用工具 `ts`、记忆前缀 `memPrefix`，产出一组 `Node`。

每个 `Node` 大致表示一个待执行步骤：

```text
id          节点ID，例如 n1
name        这一步要做什么，例如 "查询上海天气"
type        TOOL 或 SUB_AGENT
toolName    如果是 TOOL，要调用哪个工具
params      工具参数，例如 {city:"上海"}
dependsOn   依赖哪些上游节点
raceGroup   是否属于竞速组
```

所以这一步不是执行工具，而是回答一个问题：**这次请求需要拆成哪些步骤、这些步骤之间是什么依赖关系。**

例如用户问：

```text
查一下上海天气，并搜索小雨出门建议
```

可能规划出：

```text
n1: TOOL get_weather params={city:"上海"}
n2: TOOL search_web  params={query:"小雨出门建议"}
```

**② 规划为空 → 降级纯 chat**

如果 `planNodes` 是 `null` 或空列表，说明 Planner 判断这次不需要工具。此时 `runStream` 不继续建图，也不走 GraphRuntime，而是直接用普通聊天路径回答：

```java
String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix, "...简洁的AI助手...");
String answer = llm.chat(sp, histMsgs);
```

然后写入：

```text
resp.answer = answer
resp.steps  = [THOUGHT: 无需调用工具, FINAL_ANSWER: answer]
```

这不是错误处理，而是正常降级：能直接回答的问题就不强行走 ReAct。

**③ `new TaskGraph(planNodes)`：把节点列表变成可调度的任务图**

Planner 给的是 `List<Node>`，但执行时需要知道：

```text
哪些节点可以第一批跑
哪些节点必须等上游完成
哪些节点属于同一个 raceGroup
图里有没有不存在的依赖
图里有没有环
```

`TaskGraph` 就是把节点整理成一张 DAG。`GraphRuntime` 会按这张图调度节点。

如果有依赖：

```text
n1: get_weather
n2: search_web dependsOn=[n1]
```

含义就是：先查天气，再拿天气结果作为背景去搜索建议。

如果没有依赖：

```text
n1: get_weather
n2: search_web
```

含义就是：两个工具可以并行跑。

**④ `tg.validate()`：校验这张图能不能执行**

校验主要防止这几类问题：

```text
节点ID重复
dependsOn 指向不存在的节点
依赖成环，例如 n1 依赖 n2，n2 又依赖 n1
TOOL 节点缺少 toolName
SUB_AGENT 节点缺少 agentName
```

校验通过后，说明这张图至少在结构上可以调度。

**⑤ 清除所有依赖**

如果建图或校验失败，当前实现没有直接报错返回，而是清空所有节点的 `dependsOn`：

```java
for (Node n : planNodes) n.setDependsOn(new ArrayList<>());
```

这等于承认 Planner 给的依赖关系不可靠，但工具节点本身也许还能跑。清空依赖后，所有节点都会被当成第一层任务，全并行执行。

**⑥ 重建：纯并行图**

清空依赖后重新构建 `TaskGraph`：

```java
tg = new TaskGraph(planNodes);
```

这一步牺牲了“先后顺序”的准确性，换取请求还能继续走下去。风险是：如果某个节点本来必须依赖上游结果，降级后它可能拿不到上游信息，只能按自己的参数直接执行。

**⑦ 创建 `TaskState` 快照：给前端/旧接口一个可观察的任务状态**

当前源码在执行图之前，还会把 `TaskGraph` 转成旧的 `TaskState/TaskStep`：

```java
TaskState currentTask = new TaskState();
currentTask.setTaskId("task-" + System.nanoTime());
currentTask.setQuery(query);
currentTask.setStatus("running");
currentTask.setPhase("executing");
currentTask.setSteps(graphToTaskSteps(tg));
snapshots.clear();
snapshots.save(currentTask);
```

这不是工具执行逻辑，而是状态同步逻辑。作用是让前端或调试接口能看到：

```text
当前任务ID是什么
有几个步骤
每个步骤 pending/running/done/failed
每个步骤的 result/error/retryCount
```

**⑧ `rt.execute()`：真正开始按图执行工具或子 Agent**

这里才进入多步执行核心：

```java
GraphRuntime rt = new GraphRuntime(tg, cfg, GraphRuntime.Config.from(cfg),
        ts, subAgents, query, cancelled, onEvent);
GraphRuntime.GraphResult gr = rt.execute();
```

`GraphRuntime` 会做几件事：

```text
1. 按 TaskGraph 的拓扑层找出可执行节点
2. 同一层没有依赖关系的节点并行执行
3. 普通 TOOL 节点调用 tool.getExecute().apply(params)
4. SUB_AGENT 节点调用 sa.run(task, cancelled)
5. 同 raceGroup 的节点竞速，先成功的赢，其他节点跳过或取消
6. 每个节点完成后写回 Node.result / Node.status / Node.error
7. 通过 onEvent 推送 node_start / node_done / observation / race_won 等流式事件
8. 最后返回 GraphResult，里面有 observations 和 interrupted
```

所以 `runStream` 自己不直接调用 `tool.getExecute().apply(...)`。它把图交给 `GraphRuntime`，由 `GraphRuntime.invoke(node)` 分派到工具或子 Agent。

执行结束后，源码会把图状态同步回旧 `TaskState`，并转成旧格式的 `ReActStep`：

```java
syncGraphIntoTaskSteps(tg, currentTask);
snapshots.save(currentTask);
reactSteps.addAll(graphResultToReActSteps(tg));
```

这一步会生成类似：

```text
THOUGHT: 查询上海天气
ACTION: 调用 get_weather {city:"上海"}
OBSERVATION: 上海：小雨 20°C
```

**⑨ 用户取消或图内中断**

用户取消或图内中断。这里统一处理外部取消和运行时中断，设置 interrupted 响应并停止进入最终生成。

中断有两种来源：

```text
cancelled.get() == true    外部调用 cancel
gr.interrupted == true     GraphRuntime 内部检测到中断
```

一旦中断，`runStream` 不再调用 Generator，不会生成最终综合回答，而是返回：

```text
resp.answer      = "[已中断] " + 已完成/中断节点摘要
resp.steps       = 已经产生的 ReActStep
resp.task        = currentTask
resp.interrupted = true
```

**⑩ `generator.generate(...)`：把所有 observation 综合成最终回答**

如果没有中断，就进入最终生成：

```java
currentTask.setPhase("generating");
String answer = generator.generate(query, gr.observations, memPrefix, histMsgs);
```

这里传进去的 `gr.observations` 是 GraphRuntime 收集到的工具/子 Agent 原始结果，例如：

```text
[get_weather] 上海：小雨 20°C
[search_web] 小雨天出门建议：带伞，注意路滑...
```

`ChatGenerator` 会把用户问题、历史消息、记忆前缀、这些 observation 放进 prompt，让 LLM 生成一段自然语言回答。

最后写回响应和任务状态：

```text
currentTask.result = answer
currentTask.status = completed
currentTask.phase  = done
resp.answer        = answer
resp.steps         = reactSteps
resp.task          = currentTask
```

到这里，一次 ReAct 流式请求才算结束。


## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `resp` | `ChatResponse` | 被写入最终 answer / steps / task |
| `query` | `String` | 用户原始输入 |
| `ts` | `Map<String, Tool>` | 本轮可用工具集 |
| `memPrefix` | `String` | 记忆前缀 |
| `histMsgs` | `List<Map<String,String>>` | 历史消息 |
| `cancelled` | `AtomicBoolean` | 取消标志（跨线程共享） |
| `onEvent` | `Consumer<StreamEvent>` | SSE 事件回调 |

## 4. 返回值/副作用解释

**返回值**：`void`

**主流程和三处降级**：

```text
1. planGraph 规划节点
2. planNodes 为空 → 直接 LLM 聊天（降级，不报错）
3. new TaskGraph + validate
4. TaskGraph 校验失败 → 清除所有依赖，全并行执行（降级）
5. 保存 TaskState 快照
6. GraphRuntime.execute 执行工具/子 Agent
7. 图状态同步回 TaskState 和 ReActStep
8. cancelled 或 gr.interrupted → 返回部分完成结果（中断降级）
9. generator.generate 综合 observations
10. 写入 resp.answer / resp.steps / resp.task
```

## 5. 位置

```text
processInternal
  └── switch("react") → reactLoop.runStream(...)  ← 你在这里
        ├── planner.planGraph      规划节点
        ├── new TaskGraph          建图
        ├── tg.validate            校验图
        ├── snapshots.save         保存任务快照
        ├── rt.execute             执行图
        ├── graphResultToReActSteps 转回旧步骤格式
        └── generator.generate (第16章)
```

## 6. 用"查一下上海天气，并搜索小雨出门建议"跑一遍

```text
① planGraph → 3 nodes:
   n1: {tool:"get_weather", params:{city:"上海"}}
   n2: {tool:"search_web", params:{query:"小雨出门建议"}}
   n3: {tool:"rag_search", params:{query:"..."}, raceGroup:"search"}

② new TaskGraph → 建图, validate → 无环 ✓

③ graphToTaskSteps + snapshots.save:
   task.phase = executing
   steps = [get_weather, search_web/rag_search]

④ rt.execute → 拓扑2层:
   L0: [n1 独立, n2和n3 竞速(同raceGroup="search")]
   n1 runSingle → get_weather → "上海：小雨 20°C" → DONE
   n2,n3 runRace → n2 先返回 → DONE, n3 → SKIPPED

⑤ syncGraphIntoTaskSteps + graphResultToReActSteps:
   TaskStep.status/result 回填
   ReActStep 变成 THOUGHT/ACTION/OBSERVATION

⑥ cancelled=false, gr.interrupted=false → 通过

⑦ generator.generate(query, [
     "[get_weather] 上海：小雨 20°C",
     "[search_web] 小雨天出行建议..."
   ])
   → "上海目前小雨，约20°C。小雨天出行建议带伞..."
   
resp.answer = "..."
```

## 7. 常见误解

**误解一："Step 2 校验失败会直接报错"**

不会。校验失败会清除所有 `dependsOn`，把图退化成全并行。这意味着即使 Planner 产出了不合理依赖，系统也能执行（只是顺序可能不对）。

**误解二："runStream 和 ToolModeHandler.run 走同样的 LLM 总结"**

不是。`ToolModeHandler.run` 自己内联调 LLM。`runStream` 用 `ChatGenerator.generate`——专为多 observations 设计的综合 prompt。

**误解三："planGraph 返回空 List 就是失败了"**

不是。返回空 List 意味着 LLM 判断"无需工具"。这时降级到纯 chat，不会报错。
