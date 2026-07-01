# 28-GraphRuntime-doExecuteNode

这一节讲 `GraphRuntime.doExecuteNode(String nodeId, AtomicBoolean winnerFlag)`。

它是单个节点真正执行的核心方法。

`runSingle` 和 `runRace` 都会走到它：

```text
runSingle("n1")
  -> doExecuteNode("n1", null)

runRace(searchGroup)
  -> doExecuteNode("n2", winnerFound)
  -> doExecuteNode("n3", winnerFound)
```

区别是：

```text
winnerFlag == null      -> 普通节点
winnerFlag != null      -> 竞速节点
```

## 1. doExecuteNode 负责什么

它负责一个节点从“准备执行”到“执行结束”的完整状态机。

具体做这些事：

```text
1. 根据 nodeId 从 graph.nodes 取出 Node
2. 推送 node_start / step / tool_call 事件
3. 把节点状态改成 RUNNING
4. 校验工具或子 Agent 是否存在
5. 读取重试配置 maxRetries / retryDelayMs
6. 在重试循环里调用 invoke(node)
7. 处理用户取消 cancelled
8. 处理竞速已胜出 winnerFlag
9. 成功时写 DONE/result/node_done
10. 失败时写 FAILED/error/errors/observation
11. 普通节点成功时直接推 observation
12. 竞速节点成功时只返回结果，由 runRace 确认赢家后再推 observation
```

它不负责：

```text
不负责拓扑排序
不负责一层节点是否全部完成
不负责普通节点写 results
不负责竞速组最终 SKIPPED 结算
```

这些分别在 `TaskGraph`、`execute()`、`runSingle()`、`runRace()` 里完成。

## 2. 方法源码

```java
private String doExecuteNode(String nodeId, AtomicBoolean winnerFlag) {
    // ① 先从任务图里拿到当前节点对象。
    //
    // graph.getNodes() 是整张 DAG 的节点表，结构类似：
    // {
    //   "n1": Node(...),
    //   "n2": Node(...)
    // }
    //
    // nodeId 就是要执行的节点编号，比如 "n1"。
    Node node = graph.getNodes().get(nodeId);

    // 如果图里找不到这个节点，就没有任何东西可以执行。
    // 这里直接返回 null，表示“没有成功结果”。
    //
    // 注意：这里不会设置 FAILED，因为连 Node 对象都没有，
    // 也就没有地方写 status/error。
    if (node == null) return null;

    // ② 取执行体名字。
    //
    // 如果是工具节点：
    //   executor = node.toolName，比如 "get_weather"
    //
    // 如果是子 Agent 节点：
    //   executor = node.agentName，比如 "research_agent"
    //
    // 后面推事件时统一使用 executor，前端不用关心它到底是工具还是子 Agent。
    String executor = node.executorName();

    // ③ 推送 node_start 事件。
    //
    // 意思是：nodeId 这个节点开始执行了，执行体是 executor。
    // 例如：n1 开始调用 get_weather。
    onEvent.accept(StreamEvent.nodeStart(nodeId, executor));

    // ④ 推送 step 事件。
    //
    // 这个事件更像 ReAct 展示里的“第几步在做什么”。
    // idAsInt("n3") 会变成 3。
    // node.getName() 是 Planner 给这个节点起的中文任务名。
    onEvent.accept(StreamEvent.step(idAsInt(nodeId), node.getName()));

    // ⑤ 推送 tool_call 事件。
    //
    // 这里名字叫 toolCall，但子 Agent 节点也会复用这个事件。
    // 它告诉前端：当前要调用 executor，并且参数是 node.params。
    //
    // 对工具节点：
    //   executor = "search_web"
    //   params = {"query":"..."}
    //
    // 对子 Agent 节点：
    //   executor = "writer_agent"
    //   params 通常为空，因为子 Agent 主要靠 goal/upstream。
    onEvent.accept(StreamEvent.toolCall(executor, node.getParams()));

    // ⑥ 把节点状态改成 RUNNING。
    //
    // 节点刚建出来通常是 PENDING。
    // 执行到这里，表示这个节点已经真正进入执行流程。
    //
    // 后面如果校验失败，会从 RUNNING 很快变成 FAILED。
    graph.setNodeStatus(nodeId, NodeStatus.RUNNING);

    // ⑦ 执行前先校验“执行体是否存在”。
    //
    // doExecuteNode 支持两种节点：
    //   1. SUB_AGENT：去 subAgents 注册表里找 agentName
    //   2. TOOL：去 tools 表里找 toolName
    //
    // 必须先校验，否则后面 invoke(node) 里真正调用时可能空指针。
    if (node.getType() == NodeType.SUB_AGENT) {
        // ⑦-1 子 Agent 节点校验。
        //
        // node.getAgentName() 可能是：
        //   "research_agent"
        //   "writer_agent"
        //   "review_agent"
        //   "doc_agent"
        //
        // subAgents == null：
        //   说明当前运行时根本没有子 Agent 注册表。
        //
        // !subAgents.has(agentName)：
        //   说明注册表里没有这个名字。
        if (subAgents == null || !subAgents.has(node.getAgentName())) {
            String msg = "子 Agent " + node.getAgentName() + " 不存在";

            // 校验失败，节点直接标 FAILED。
            graph.setNodeStatus(nodeId, NodeStatus.FAILED);

            // 把错误写回 Node，方便前端或最终结果查看。
            graph.setNodeError(nodeId, msg);

            // 推 observation，告诉前端这一步的观察结果就是错误信息。
            onEvent.accept(StreamEvent.observation(executor, msg));

            // errors 是 GraphRuntime 里的全局错误表。
            //
            // 因为可能有多个节点并发写 errors，所以用 synchronized(mu)
            // 保护这次写入，避免并发读写错乱。
            synchronized (mu) { errors.put(nodeId, msg); }

            // 返回 null 表示这个节点没有成功结果。
            return null;
        }
    } else {
        // ⑦-2 工具节点校验。
        //
        // node.getToolName() 可能是：
        //   "get_weather"
        //   "search_web"
        //   "rag_search"
        //
        // tools 是允许调用的工具表：
        //   toolName -> Tool 对象
        //
        // 如果 tools 里没有这个名字，就不能执行。
        if (tools.get(node.getToolName()) == null) {
            String msg = "工具 " + node.getToolName() + " 不在允许列表中";

            // 和子 Agent 校验失败一样：
            // 标失败、写错误、推 observation、写 errors、返回 null。
            graph.setNodeStatus(nodeId, NodeStatus.FAILED);
            graph.setNodeError(nodeId, msg);
            onEvent.accept(StreamEvent.observation(executor, msg));
            synchronized (mu) { errors.put(nodeId, msg); }
            return null;
        }
    }

    // ⑧ 读取重试配置。
    //
    // 这里读的是 appCfg.getHarness()，也就是测试/执行 harness 配置。
    // 如果配置对象为空，就使用默认值：
    //   maxRetries = 3
    //   retryDelay = 200ms
    AppConfig.HarnessConfig h = appCfg.getHarness();

    // maxRetries 表示最多调用 invoke(node) 几次。
    // 例如 maxRetries = 3，就是最多尝试 3 次。
    int maxRetries = h == null ? 3 : h.getMaxRetries();

    // retryDelay 表示失败后下一次重试前 sleep 多久。
    // 单位是毫秒。
    int retryDelay = h == null ? 200 : h.getRetryDelayMs();

    // ⑨ 准备两个局部变量记录执行结果。
    //
    // result:
    //   成功时保存 invoke(node) 返回的结果字符串。
    //
    // lastErr:
    //   失败时保存最近一次错误信息。
    //
    // 最后会根据 lastErr 判断：
    //   lastErr == null  -> 成功 DONE
    //   lastErr != null  -> 失败 FAILED
    String result = null;
    String lastErr = null;

    // ⑩ 重试循环。
    //
    // attempt 从 0 开始。
    // 如果 maxRetries = 3，循环会尝试：
    //   attempt = 0  第 1 次
    //   attempt = 1  第 2 次
    //   attempt = 2  第 3 次
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        // ⑩-1 每次尝试前先检查用户是否取消。
        //
        // cancelled 是 AtomicBoolean，整个图执行共享。
        // 用户取消后它会变成 true。
        if (cancelled.get()) {
            // 如果用户取消，把当前节点标成 CANCELLED。
            graph.setNodeStatus(nodeId, NodeStatus.CANCELLED);

            // lastErr 写成“被用户中断”。
            // 注意：后面统一用 lastErr != null 走失败收尾逻辑。
            lastErr = "被用户中断";

            // break 是跳出重试循环，不再执行 invoke。
            break;
        }

        // ⑩-2 如果这是竞速节点，检查同组是否已经有赢家。
        //
        // 普通节点：
        //   winnerFlag == null
        //
        // 竞速节点：
        //   winnerFlag 指向 runRace 里的 winnerFound
        //
        // 如果 winnerFlag.get() == true，说明同组其他节点已经赢了，
        // 当前节点不需要继续执行，也不需要继续重试。
        if (winnerFlag != null && winnerFlag.get()) {
            // 这里直接返回 null。
            //
            // 注意：这里不把当前节点标 FAILED。
            // 竞速组的最终结算由 runRace 负责：
            //   有赢家 -> 其他节点标 SKIPPED
            //   没赢家 -> 整组标 FAILED
            return null;
        }

        try {
            // ⑩-3 真正执行节点。
            //
            // invoke(node) 内部会继续分派：
            //   TOOL      -> t.getExecute().apply(params)
            //   SUB_AGENT -> sa.run(task, cancelled)
            //
            // 这是本方法里真正发生工具/子 Agent 调用的地方。
            result = invoke(node);

            // 如果 invoke 没抛异常，说明这次尝试成功。
            // lastErr 清空，表示最后状态应该是 DONE。
            lastErr = null;

            // 成功后不需要继续重试，跳出 for 循环。
            break;
        } catch (Exception e) {
            // ⑩-4 invoke 抛异常，说明本次尝试失败。
            //
            // 优先取 e.getMessage()。
            // 如果 message 是 null，就用异常类名兜底，比如 NullPointerException。
            lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();

            // 记录当前节点已经重试/尝试到第几次。
            //
            // attempt 从 0 开始，所以展示给外部时 +1。
            // attempt = 0 -> retryCount = 1
            // attempt = 1 -> retryCount = 2
            graph.setNodeRetryCount(nodeId, attempt + 1);

            // 失败后再检查一次取消。
            //
            // 因为 invoke(node) 可能执行了很久，
            // 执行期间用户可能已经点了取消。
            if (cancelled.get()) {
                lastErr = "被用户中断";
                graph.setNodeStatus(nodeId, NodeStatus.CANCELLED);
                break;
            }

            // ⑩-5 如果还没到最后一次尝试，就睡一会儿再重试。
            //
            // attempt < maxRetries - 1 表示：
            //   当前不是最后一次。
            //
            // 例如 maxRetries = 3：
            //   attempt = 0 可以 sleep 后重试
            //   attempt = 1 可以 sleep 后重试
            //   attempt = 2 是最后一次，不再 sleep
            if (attempt < maxRetries - 1) {
                try { Thread.sleep(retryDelay); }
                catch (InterruptedException ignored) {
                    // sleep 期间被 interrupt，常见于：
                    //   1. 用户取消
                    //   2. runRace 已经有赢家，打断其他竞速线程
                    //
                    // 先恢复当前线程的中断标记。
                    Thread.currentThread().interrupt();

                    // 然后直接返回 null。
                    // 这表示当前节点没有成功结果。
                    return null;
                }
            }
        }
    }

    // ⑪ 如果 lastErr 不为空，说明最终没有成功结果。
    //
    // 可能原因：
    //   - 工具/子 Agent 连续失败
    //   - 用户取消
    //   - invoke 抛异常且重试耗尽
    if (lastErr != null) {
        // 标记节点失败。
        //
        // 注意：如果前面曾经标过 CANCELLED，这里会覆盖成 FAILED。
        // 当前源码就是这样写的；最终错误信息仍然会是“被用户中断”。
        graph.setNodeStatus(nodeId, NodeStatus.FAILED);

        // 把最后一次错误写进 Node。
        graph.setNodeError(nodeId, lastErr);

        // 写入 GraphRuntime 的全局错误表。
        // 多线程环境下用 synchronized(mu) 保护。
        synchronized (mu) { errors.put(nodeId, lastErr); }

        // 推 observation，让前端和最终观察结果能看到失败原因。
        onEvent.accept(StreamEvent.observation(executor, "执行失败: " + lastErr));

        // 返回 null 表示没有成功结果。
        return null;
    }

    // ⑫ 走到这里，说明最后一次 invoke 成功了。
    //
    // 节点状态改成 DONE。
    graph.setNodeStatus(nodeId, NodeStatus.DONE);

    // 写入节点结果。
    //
    // 如果执行体返回 Java null，这里转成空字符串。
    // 这样 Node.result 不会是 null。
    graph.setNodeResult(nodeId, result == null ? "" : result);

    // 推 node_done 事件，告诉前端：
    //   nodeId 这个节点已经 DONE。
    onEvent.accept(StreamEvent.nodeDone(nodeId, executor, NodeStatus.DONE.value()));

    // ⑬ 普通节点成功后，立刻推 observation。
    //
    // winnerFlag == null 表示普通节点，也就是 runSingle 调用进来的。
    //
    // 竞速节点不能在这里立刻推 observation。
    // 因为竞速节点只是“自己执行成功”，还不等于“自己是赢家”。
    // 竞速结果要交给 runRace 用 compareAndSet 确认赢家后再推。
    if (winnerFlag == null) {
        onEvent.accept(StreamEvent.observation(executor, result == null ? "" : result));
    }

    // ⑭ 返回执行结果。
    //
    // 返回非 null 字符串表示成功。
    // 如果 result 是 Java null，就返回 ""，仍然表示成功，只是内容为空。
    //
    // runSingle 会用 r != null 判断是否写入 results。
    // runRace 会把这个返回值包装成 RaceAttempt，交给竞速收集逻辑判断赢家。
    return result == null ? "" : result;
}
```

## 3. 参数和返回值

方法签名：

```java
private String doExecuteNode(String nodeId, AtomicBoolean winnerFlag)
```

两个参数：

| 参数 | 含义 | 普通节点 | 竞速节点 |
|---|---|---|---|
| `nodeId` | 要执行的节点 ID | `"n1"` | `"n2"` / `"n3"` |
| `winnerFlag` | 竞速组是否已经有赢家 | `null` | `AtomicBoolean winnerFound` |

返回值：

| 返回值 | 含义 |
|---|---|
| 非 `null` 字符串 | 节点执行成功，返回工具或子 Agent 的结果 |
| `""` 空字符串 | 节点执行成功，但执行体返回了 Java `null` |
| `null` | 节点不存在、校验失败、执行失败、被取消、竞速中别人已胜出、线程被 interrupt |

注意这个区别：

```text
return ""    -> 成功，只是结果内容为空
return null  -> 没有成功结果
```

这也是 `runSingle` 里为什么写：

```java
if (r != null) results.put(nodeId, r);
```

## 4. 第一步：从 graph 里取 Node

```java
Node node = graph.getNodes().get(nodeId);
if (node == null) return null;
```

`graph.getNodes()` 是整张任务图的节点表。

大概长这样：

```text
graph.nodes = {
  "n1": Node(...),
  "n2": Node(...),
  "n3": Node(...)
}
```

如果传进来：

```text
nodeId = "n1"
```

就取：

```text
graph.nodes["n1"]
```

如果没有这个节点：

```text
node == null
```

方法直接：

```java
return null;
```

这种情况不会继续推事件，也不会写状态，因为连节点对象都没有。

## 5. executorName：统一拿执行体名字

```java
String executor = node.executorName();
```

`Node.executorName()` 源码是：

```java
public String executorName() {
    if (type == NodeType.SUB_AGENT) return agentName == null ? "" : agentName;
    return toolName == null ? "" : toolName;
}
```

它的作用是统一拿“真正要执行的东西叫什么”。

工具节点：

```text
node.type = TOOL
node.toolName = "get_weather"

executor = "get_weather"
```

子 Agent 节点：

```text
node.type = SUB_AGENT
node.agentName = "doc_agent"

executor = "doc_agent"
```

之后的事件都用 `executor`：

```text
node_start 里显示 executor
tool_call 里显示 executor
observation 里显示 executor
```

这样前端不用关心它是工具还是子 Agent，只看到“当前执行体名称”。

## 6. 三个开始事件

```java
onEvent.accept(StreamEvent.nodeStart(nodeId, executor));
onEvent.accept(StreamEvent.step(idAsInt(nodeId), node.getName()));
onEvent.accept(StreamEvent.toolCall(executor, node.getParams()));
```

这三行都是 SSE 事件。

### 6.1 node_start

```java
StreamEvent.nodeStart(nodeId, executor)
```

事件数据：

```text
type = "node_start"
data = {
  "id": "n1",
  "tool": "get_weather"
}
```

它告诉前端：

```text
n1 这个节点开始执行了
执行体是 get_weather
```

### 6.2 step

```java
StreamEvent.step(idAsInt(nodeId), node.getName())
```

如果：

```text
nodeId = "n1"
node.name = "查询上海天气"
```

那么：

```text
idAsInt("n1") = 1
```

事件数据：

```text
type = "step"
data = {
  "idx": 1,
  "name": "查询上海天气"
}
```

这是给前端展示 ReAct 步骤用的。

### 6.3 tool_call

```java
StreamEvent.toolCall(executor, node.getParams())
```

如果：

```text
executor = "get_weather"
node.params = {"city":"上海"}
```

事件、据：

```text
type = "tool_call"
data = {
  "tool": "get_weather",
  "params": {"city":"上海"}
}
```

这里叫 `tool_call`，但子 Agent 节点也会复用这个事件类型。

## 7. 状态改成 RUNNING

```java
graph.setNodeStatus(nodeId, NodeStatus.RUNNING);
```

节点默认状态是：

```text
PENDING
```

执行到这里后变成：

```text
RUNNING
```

状态变化：

```text
PENDING -> RUNNING
```

这一步表示：节点已经进入执行流程。

注意：状态变成 `RUNNING` 后，才开始校验工具或子 Agent 是否存在。

所以如果工具不存在，状态会很快从：

```text
RUNNING -> FAILED
```

## 8. 校验子 Agent 是否存在

```java
if (node.getType() == NodeType.SUB_AGENT) {
    if (subAgents == null || !subAgents.has(node.getAgentName())) {
        String msg = "子 Agent " + node.getAgentName() + " 不存在";
        graph.setNodeStatus(nodeId, NodeStatus.FAILED);
        graph.setNodeError(nodeId, msg);
        onEvent.accept(StreamEvent.observation(executor, msg));
        synchronized (mu) { errors.put(nodeId, msg); }
        return null;
    }
}
```

如果当前节点是：

```text
type = SUB_AGENT
agentName = "doc_agent"
```

代码会检查：

```text
subAgents 是否存在
subAgents.has("doc_agent") 是否为 true
```

如果不存在，直接失败：

```text
msg = "子 Agent doc_agent 不存在"

graph.nodes["n5"].status = FAILED
graph.nodes["n5"].error = "子 Agent doc_agent 不存在"
errors["n5"] = "子 Agent doc_agent 不存在"
```

还会推一个 observation：

```text
type = "observation"
data = {
  "tool": "doc_agent",
  "result": "子 Agent doc_agent 不存在"
}
```

最后：

```java
return null;
```

表示没有成功结果。

## 9. 校验工具是否存在

```java
else {
    if (tools.get(node.getToolName()) == null) {
        String msg = "工具 " + node.getToolName() + " 不在允许列表中";
        graph.setNodeStatus(nodeId, NodeStatus.FAILED);
        graph.setNodeError(nodeId, msg);
        onEvent.accept(StreamEvent.observation(executor, msg));
        synchronized (mu) { errors.put(nodeId, msg); }
        return null;
    }
}
```

如果当前节点是普通工具节点：

```text
type = TOOL
toolName = "get_weather"
```

代码会检查：

```text
tools.get("get_weather") 是否存在
```

如果工具没有注册：

```text
tools.get("get_weather") == null
```

直接失败：

```text
msg = "工具 get_weather 不在允许列表中"

graph.nodes["n1"].status = FAILED
graph.nodes["n1"].error = "工具 get_weather 不在允许列表中"
errors["n1"] = "工具 get_weather 不在允许列表中"
```

然后返回：

```java
return null;
```

为什么要提前校验？

因为之后真正调用工具时会执行：

```java
Tool t = tools.get(node.getToolName());
return t.getExecute().apply(params);
```

如果不先校验，`t` 可能是 `null`，再调用 `t.getExecute()` 就会空指针。

## 10. 读取重试配置

```java
AppConfig.HarnessConfig h = appCfg.getHarness();
int maxRetries = h == null ? 3 : h.getMaxRetries();
int retryDelay = h == null ? 200 : h.getRetryDelayMs();
```

这里读的是 `HarnessConfig`，不是 `GraphConfig`。

默认值在配置类里是：

```text
maxRetries = 3
retryDelayMs = 200
```

含义：

```text
maxRetries: 最多尝试执行几次 invoke(node)
retryDelay: 两次尝试之间睡多久，单位毫秒
```

如果：

```text
maxRetries = 3
retryDelay = 200
```

最多会这样跑：

```text
attempt = 0  第 1 次调用 invoke
失败 -> sleep 200ms

attempt = 1  第 2 次调用 invoke
失败 -> sleep 200ms

attempt = 2  第 3 次调用 invoke
失败 -> 不再 sleep，结束循环
```

## 11. result 和 lastErr

```java
String result = null;
String lastErr = null;
```

这两个变量记录当前节点的执行结果。

```text
result:  成功结果
lastErr: 最近一次错误
```

开始时：

```text
result = null
lastErr = null
```

成功后：

```text
result = "上海：小雨 20°C"
lastErr = null
```

失败后：

```text
result = null
lastErr = "HTTP 500"
```

最后方法通过 `lastErr` 判断这次节点是成功还是失败：

```text
lastErr != null -> FAILED
lastErr == null -> DONE
```

## 12. 重试循环整体结构

```java
for (int attempt = 0; attempt < maxRetries; attempt++) {
    ...
}
```

如果：

```text
maxRetries = 3
```

循环次数是：

```text
attempt = 0
attempt = 1
attempt = 2
```

注意变量名叫 `attempt`，但代码写重试次数时是：

```java
graph.setNodeRetryCount(nodeId, attempt + 1);
```

所以第一次失败会写：

```text
retryCount = 1
```

第二次失败会写：

```text
retryCount = 2
```

第三次失败会写：

```text
retryCount = 3
```

## 13. 检查用户取消

```java
if (cancelled.get()) {
    graph.setNodeStatus(nodeId, NodeStatus.CANCELLED);
    lastErr = "被用户中断";
    break;
}
```

每次调用工具前，都会先检查：

```text
cancelled == true ?
```

如果用户已经取消整次执行：

```text
graph.nodes[nodeId].status = CANCELLED
lastErr = "被用户中断"
break
```

这里是 `break`，不是 `return`。

所以代码会跳出重试循环，继续走到下面：

```java
if (lastErr != null) {
    graph.setNodeStatus(nodeId, NodeStatus.FAILED);
    ...
    return null;
}
```

这意味着当前实现里，取消分支先把节点设成 `CANCELLED`，随后又会被失败结算覆盖成 `FAILED`。

按源码真实效果看，最终状态更可能是：

```text
FAILED
```

但错误信息是：

```text
"被用户中断"
```

这个细节很重要：文档不能只写“取消后保持 CANCELLED”，因为源码之后会继续按 `lastErr != null` 走失败结算。

## 14. 检查竞速是否已经有人胜出

```java
if (winnerFlag != null && winnerFlag.get()) {
    // 竞速：他人胜出 → 不再重试，直接返回让外层标 SKIPPED
    return null;
}
```

普通节点：

```text
winnerFlag = null
```

这个判断直接跳过。

竞速节点：

```text
winnerFlag = winnerFound
```

如果同组别的节点已经赢了：

```text
winnerFlag.get() == true
```

当前节点直接：

```java
return null;
```

它不会继续调用工具，也不会在这里把自己标成 `SKIPPED`。

`SKIPPED` 是 `runRace` 结算时统一标记的：

```text
winnerFound == true
非 DONE 节点 -> SKIPPED
```

## 15. 真正执行：invoke(node)

```java
try {
    result = invoke(node);
    lastErr = null;
    break;
} catch (Exception e) {
    ...
}
```

真正执行工具或子 Agent 的地方是：

```java
invoke(node)
```

`invoke` 里面再分两种：

```text
SUB_AGENT -> sa.run(task, cancelled)
TOOL      -> t.getExecute().apply(params)
```

工具节点例子：

```text
node.toolName = "get_weather"
node.params = {"city":"上海"}
```

调用链：

```text
doExecuteNode("n1", null)
  -> invoke(node)
      -> tools.get("get_weather")
      -> params = {"city":"上海"}
      -> t.getExecute().apply(params)
      -> "上海：小雨 20°C"
```

如果 `invoke(node)` 没抛异常：

```text
result = "上海：小雨 20°C"
lastErr = null
break
```

`break` 的意思是：成功了，不再重试。

## 16. 捕获异常和记录 retryCount

```java
catch (Exception e) {
    lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    graph.setNodeRetryCount(nodeId, attempt + 1);
    ...
}
```

如果 `invoke(node)` 抛异常：

```text
throw new RuntimeException("HTTP 500")
```

那么：

```text
lastErr = "HTTP 500"
retryCount = attempt + 1
```

例如：

```text
attempt = 0
retryCount = 1
```

如果异常没有 message：

```text
e.getMessage() == null
```

就用异常类名：

```text
lastErr = e.getClass().getSimpleName()
```

例如：

```text
lastErr = "SocketTimeoutException"
```

## 17. 异常后再次检查取消

```java
if (cancelled.get()) {
    lastErr = "被用户中断";
    graph.setNodeStatus(nodeId, NodeStatus.CANCELLED);
    break;
}
```

为什么 catch 里还要检查一次取消？

因为可能出现这种时序：

```text
工具调用中
用户点击取消
工具抛出异常返回
进入 catch
```

这时候错误原因不应该只写成工具异常，而应该写成：

```text
被用户中断
```

所以 catch 里再次检查 `cancelled`。

不过和前面一样，这里 `break` 后仍然会进入 `lastErr != null` 的失败结算。

## 18. 失败后 sleep，再重试

```java
if (attempt < maxRetries - 1) {
    try { Thread.sleep(retryDelay); }
    catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        // 竞速 / 取消引起的 interrupt → 立即返回
        return null;
    }
}
```

如果这不是最后一次尝试，就等一会儿再重试。

例如：

```text
maxRetries = 3
retryDelay = 200
```

执行过程：

```text
attempt = 0 失败 -> sleep 200ms
attempt = 1 失败 -> sleep 200ms
attempt = 2 失败 -> 不 sleep，结束
```

为什么 `sleep` 会被打断？

因为 `runRace` 里，如果某个竞速节点已经赢了，会对其他候选线程执行：

```java
t.interrupt();
```

如果失败者正好在：

```java
Thread.sleep(retryDelay)
```

就会抛出：

```java
InterruptedException
```

代码捕获后：

```java
Thread.currentThread().interrupt();
return null;
```

意思是：恢复线程中断标记，并直接返回，不再重试。

## 19. 失败结算：lastErr != null

```java
if (lastErr != null) {
    graph.setNodeStatus(nodeId, NodeStatus.FAILED);
    graph.setNodeError(nodeId, lastErr);
    synchronized (mu) { errors.put(nodeId, lastErr); }
    onEvent.accept(StreamEvent.observation(executor, "执行失败: " + lastErr));
    return null;
}
```

这里的：

```java
synchronized (mu) { errors.put(nodeId, lastErr); }
```

意思是：写 `errors` 这个共享 Map 前，先拿 `mu` 这把锁。

`mu` 在 `GraphRuntime` 里定义为：

```java
private final Object mu = new Object();
```

它只是一个专门当锁用的对象。`synchronized (mu)` 会让同一时间只有一个线程进入这段写共享状态的代码，避免多个节点并行失败时一起改 `errors`。

只要循环结束后 `lastErr` 还有值，就按失败处理。

假设：

```text
nodeId = "n1"
lastErr = "HTTP 500"
executor = "get_weather"
```

执行后：

```text
graph.nodes["n1"].status = FAILED
graph.nodes["n1"].error = "HTTP 500"
errors["n1"] = "HTTP 500"
```

推送事件：

```text
type = "observation"
data = {
  "tool": "get_weather",
  "result": "执行失败: HTTP 500"
}
```

返回：

```java
return null;
```

所以调用方会知道：这个节点没有成功结果。

## 20. 成功结算：DONE/result/node_done

如果没有错误：

```java
graph.setNodeStatus(nodeId, NodeStatus.DONE);
graph.setNodeResult(nodeId, result == null ? "" : result);
onEvent.accept(StreamEvent.nodeDone(nodeId, executor, NodeStatus.DONE.value()));
```

假设：

```text
nodeId = "n1"
executor = "get_weather"
result = "上海：小雨 20°C"
```

执行后：

```text
graph.nodes["n1"].status = DONE
graph.nodes["n1"].result = "上海：小雨 20°C"
```

推送事件：

```text
type = "node_done"
data = {
  "id": "n1",
  "tool": "get_weather",
  "status": "done"
}
```

如果 `result == null`，会写成空字符串：

```text
graph.nodes["n1"].result = ""
```

## 21. 普通节点和竞速节点的 observation 区别

```java
if (winnerFlag == null) {
    onEvent.accept(StreamEvent.observation(executor, result == null ? "" : result));
}
```

普通节点：

```text
winnerFlag == null
```

成功后 `doExecuteNode` 直接发送 observation：

```text
type = "observation"
data = {
  "tool": "get_weather",
  "result": "上海：小雨 20°C"
}
```

竞速节点：

```text
winnerFlag != null
```

这里不会发送 observation。

原因是：竞速节点执行成功，不代表它一定是赢家。

例如：

```text
n2 成功返回
n3 也成功返回
```

最后只有先通过 `winnerFound.compareAndSet(false, true)` 的那个节点才算赢家。

所以竞速节点的 observation 在 `runRace` 里发送：

```text
runRace 确认赢家
  -> raceWon
  -> observation
```

## 22. 最后一行返回值

```java
return result == null ? "" : result;
```

这行保证：只要走到成功结算，返回值就不是 Java `null`。

情况一：

```text
result = "上海：小雨 20°C"
return "上海：小雨 20°C"
```

情况二：

```text
result = null
return ""
```

这对调用方很重要。

`runSingle` 会根据返回值决定是否写 `results`：

```java
if (r != null) results.put(nodeId, r);
```

所以：

```text
return ""    -> 写 results，值为空字符串
return null  -> 不写 results
```

## 23. 完整例子：普通工具节点成功

节点：

```text
nodeId = "n1"
type = TOOL
name = "查询上海天气"
toolName = "get_weather"
params = {"city":"上海"}
winnerFlag = null
```

执行：

```text
doExecuteNode("n1", null)

1. node = graph.nodes["n1"]
2. executor = "get_weather"

3. 发送 node_start
   {"id":"n1","tool":"get_weather"}

4. 发送 step
   {"idx":1,"name":"查询上海天气"}

5. 发送 tool_call
   {"tool":"get_weather","params":{"city":"上海"}}

6. status:
   PENDING -> RUNNING

7. 校验工具:
   tools.get("get_weather") != null

8. 读取重试:
   maxRetries = 3
   retryDelay = 200

9. attempt = 0
   cancelled == false
   winnerFlag == null
   invoke(node)
     -> t.getExecute().apply({"city":"上海"})
     -> "上海：小雨 20°C"
   result = "上海：小雨 20°C"
   lastErr = null
   break

10. lastErr == null，所以成功

11. status:
    RUNNING -> DONE

12. node.result:
    "上海：小雨 20°C"

13. 发送 node_done
    {"id":"n1","tool":"get_weather","status":"done"}

14. 因为 winnerFlag == null，发送 observation
    {"tool":"get_weather","result":"上海：小雨 20°C"}

15. return "上海：小雨 20°C"
```

## 24. 完整例子：工具第一次失败，第二次成功

节点还是：

```text
n1: get_weather({"city":"上海"})
```

配置：

```text
maxRetries = 3
retryDelay = 200
```

执行：

```text
attempt = 0
  invoke(node)
    -> 抛异常 RuntimeException("HTTP 500")
  lastErr = "HTTP 500"
  retryCount = 1
  cancelled == false
  attempt < 2，所以 sleep 200ms

attempt = 1
  invoke(node)
    -> 返回 "上海：小雨 20°C"
  result = "上海：小雨 20°C"
  lastErr = null
  break

lastErr == null
status = DONE
node.result = "上海：小雨 20°C"
return "上海：小雨 20°C"
```

最终：

```text
graph.nodes["n1"].status = DONE
graph.nodes["n1"].retryCount = 1
graph.nodes["n1"].result = "上海：小雨 20°C"
```

## 25. 完整例子：竞速节点发现别人已经赢了

竞速组：

```text
race_group = "search"

n2: tavily_search
n3: rag_search
```

假设 `n3` 已经赢了：

```text
winnerFound = true
```

现在 `n2` 进入：

```java
doExecuteNode("n2", winnerFound)
```

执行到循环里：

```java
if (winnerFlag != null && winnerFlag.get()) {
    return null;
}
```

因为：

```text
winnerFlag != null
winnerFlag.get() == true
```

所以：

```text
return null
```

这里不会写：

```text
n2.status = SKIPPED
```

`SKIPPED` 会由 `runRace` 的最后结算统一写：

```text
winnerFound == true
n2 不是 DONE
-> n2.status = SKIPPED
```

## 26. 完整例子：工具不存在

节点：

```text
nodeId = "n9"
type = TOOL
toolName = "fake_tool"
params = {}
```

执行：

```text
doExecuteNode("n9", null)

1. node = graph.nodes["n9"]
2. executor = "fake_tool"
3. 发送 node_start / step / tool_call
4. status = RUNNING
5. tools.get("fake_tool") == null
6. msg = "工具 fake_tool 不在允许列表中"
7. status = FAILED
8. node.error = msg
9. errors["n9"] = msg
10. 发送 observation("fake_tool", msg)
11. return null
```

注意：工具不存在时不会进入重试循环。

因为这不是临时失败，而是规划出来的执行体不存在。

## 27. 状态流转总结

`doExecuteNode` 直接处理的状态：

```text
PENDING -> RUNNING -> DONE
PENDING -> RUNNING -> FAILED
PENDING -> RUNNING -> CANCELLED -> FAILED   当前源码最终会被失败结算覆盖
```

`SKIPPED` 不在 `doExecuteNode` 里最终结算。

竞速失败者的状态由 `runRace` 写：

```text
runRace:
  winnerFound == true
  非 DONE 节点 -> SKIPPED
```

## 28. 常见误解

### 28.1 doExecuteNode 会不会把结果写入 results

不会。

它只写：

```text
graph.nodes[nodeId].result
graph.nodes[nodeId].status
graph.nodes[nodeId].error
errors[nodeId]
```

`results` 是外层写的：

```text
runSingle: 普通节点成功后写 results
runRace: 赢家确认后写 results
```

### 28.2 doExecuteNode 失败会不会把异常抛出去

一般不会。

`invoke(node)` 抛出的 `Exception` 会在重试循环里被 catch。

最终失败时：

```text
status = FAILED
error = lastErr
return null
```

但注意：这里 catch 的是 `Exception`，不是所有 `Throwable`。

`runRace` 的候选线程外面还有一层：

```java
catch (Throwable ex)
```

所以竞速线程里更大的错误会被 `runRace` 包住，并投递成 `RaceAttempt`。

### 28.3 observation 是不是每次成功都会在这里发

不是。

普通节点成功：

```text
doExecuteNode 里发 observation
```

竞速节点成功：

```text
doExecuteNode 不发 observation
runRace 确认赢家后发 observation
```

校验失败或最终执行失败：

```text
doExecuteNode 会发 observation，内容是错误信息
```

### 28.4 retryCount 是重试了几次还是尝试了几次

当前代码里：

```java
graph.setNodeRetryCount(nodeId, attempt + 1);
```

它在每次失败后写入。

所以第一次调用失败时：

```text
retryCount = 1
```

严格说它更像“失败尝试次数”，不是“额外重试次数”。

### 28.5 取消后最终一定是 CANCELLED 吗

按当前源码，不一定。

代码先写：

```text
status = CANCELLED
lastErr = "被用户中断"
break
```

然后会进入：

```text
lastErr != null
status = FAILED
error = "被用户中断"
```

所以最终可能表现为：

```text
status = FAILED
error = "被用户中断"
```

如果想让最终状态保持 `CANCELLED`，源码需要在取消分支直接 `return null`，或者失败结算时区分取消错误。

## 29. 一句话总结

`doExecuteNode` 是单节点执行状态机：

```text
取 Node
  -> 推 node_start / step / tool_call
  -> status = RUNNING
  -> 校验工具或子 Agent
  -> 读取重试配置
  -> 循环 invoke(node)
  -> 成功：DONE + result + node_done + 返回结果
  -> 失败：FAILED + error + errors + observation + 返回 null
```

普通节点和竞速节点最大的区别是：

```text
普通节点 winnerFlag == null:
  成功 observation 在 doExecuteNode 里发

竞速节点 winnerFlag != null:
  doExecuteNode 只返回候选结果
  observation 由 runRace 确认赢家后发
```
