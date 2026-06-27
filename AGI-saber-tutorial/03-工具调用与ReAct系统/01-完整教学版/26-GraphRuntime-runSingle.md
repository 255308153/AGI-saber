# 26-GraphRuntime-runSingle

这一节讲 `GraphRuntime.runSingle(String nodeId)`。

它负责执行一个**普通节点**：不是竞速节点，不需要抢赢家，只要把这个节点对应的工具或子 Agent 跑完，然后把结果写回 `results`。

源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/application/chat/GraphRuntime.java
```

## 1. runSingle 解决什么问题

`TaskGraph` 会把节点分成一层一层执行。

如果某一层里有普通节点：

```text
level = ["n1", "n4"]
```

`GraphRuntime.execute()` 会把它们提交到线程池：

```java
pool.execute(() -> {
    try { runSingle(id); } finally { innerLatch.countDown(); }
});
```

也就是说：

```text
线程 A 执行 runSingle("n1")
线程 B 执行 runSingle("n4")
```

`runSingle` 要处理四件事：

```text
1. 先拿并发许可，避免同时跑太多节点
2. 执行这个节点
3. 如果执行成功，把结果写入 results
4. 无论成功失败，都归还并发许可
```

这就是它的完整职责。它不负责拆 DAG，不负责拓扑排序，也不负责判断这一层什么时候结束；这些在 `execute()` 和 `CountDownLatch` 那边完成。

## 2. 方法源码

```java
private void runSingle(String nodeId) {
    acquire();
    try {
        String r = doExecuteNode(nodeId, null);
        synchronized (mu) {
            if (r != null) results.put(nodeId, r);
        }
    } finally {
        sem.release();
    }
}
```

这段代码很短，但是每一行都在控制运行时状态。

## 3. 第一行：acquire()

```java
acquire();
```

`acquire()` 的源码是：

```java
private void acquire() {
    try { sem.acquire(); } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

这里的 `sem` 是 `Semaphore`：

```java
private final Semaphore sem;

this.sem = new Semaphore(this.cfg.maxParallel);
```

`Semaphore` 中文通常叫**信号量**。

你可以把它理解成一个“并发许可证盒子”：

```text
cfg.maxParallel = 2

许可证盒子里一开始有 2 张许可证：
[permit, permit]
```

每个普通节点真正执行前，都要先拿一张许可证：

```text
runSingle("n1") -> acquire() -> 拿走 1 张
runSingle("n4") -> acquire() -> 再拿走 1 张
runSingle("n7") -> acquire() -> 没许可证了，卡住等待
```

等前面的节点执行完，调用：

```java
sem.release();
```

许可证被放回去：

```text
runSingle("n1") 执行完 -> release() -> 放回 1 张
runSingle("n7") 等到了许可证 -> 继续执行
```

所以 `acquire()` 不是执行工具，它只是限制“最多同时有几个节点在执行”。

### 3.1 一个具体并发例子

假设当前层有两个普通节点：

```text
level = ["n1", "n4"]
```

`execute()` 提交任务：

```text
pool.execute(() -> runSingle("n1"))
pool.execute(() -> runSingle("n4"))
```

如果配置是：

```text
cfg.maxParallel = 1
```

运行过程会变成：

```text
线程 A：runSingle("n1")
  acquire() 成功，许可证从 1 变成 0
  开始执行 n1

线程 B：runSingle("n4")
  acquire() 发现许可证是 0
  在这里等待

线程 A：n1 执行完
  finally 里 sem.release()
  许可证从 0 变成 1

线程 B：等到许可证
  acquire() 返回
  开始执行 n4
```

如果配置是：

```text
cfg.maxParallel = 2
```

那 `n1` 和 `n4` 可以同时进入 `doExecuteNode()`。

## 4. 第二步：doExecuteNode(nodeId, null)

```java
String r = doExecuteNode(nodeId, null);
```

这一行才是真正执行节点。

`nodeId` 是节点编号，例如：

```text
nodeId = "n1"
```

第二个参数传的是：

```java
null
```

这个参数叫 `winnerFlag`，只给竞速节点使用。

普通节点传 `null`，意思是：

```text
这个节点不是 race_group 竞速节点
不用判断别人是否已经赢了
不用等 runRace 确认赢家
执行成功后可以直接发送 observation
```

`doExecuteNode(nodeId, null)` 内部会做这些事：

```text
1. 根据 nodeId 从 graph.nodes 里取 Node
2. 推送 nodeStart 事件
3. 推送 step 事件
4. 推送 toolCall 事件，把工具名和参数告诉前端
5. 把节点状态改成 RUNNING
6. 校验工具或子 Agent 是否存在
7. 按 maxRetries 重试执行 invoke(node)
8. 成功：节点状态改成 DONE，写入 node.result，推送 nodeDone 和 observation
9. 失败：节点状态改成 FAILED，写入 node.error，记录 errors
10. 取消：节点状态改成 CANCELLED
11. 返回执行结果字符串；失败或被竞速中断时返回 null
```

注意：`runSingle` 本身没有直接调用工具。它是通过：

```text
runSingle
  -> doExecuteNode
      -> invoke
          -> t.getExecute().apply(params)
```

最终才调到工具函数。

## 5. doExecuteNode 里怎么调用工具

工具节点会进入 `invoke(Node node)`：

```java
private String invoke(Node node) throws Exception {
    if (node.getType() == NodeType.SUB_AGENT) {
        SubAgent sa = subAgents.get(node.getAgentName());
        Map<String, String> upstream = upstreamResults(node);
        SubAgentTask task = new SubAgentTask(node.getId(), node.getGoal(), taskQuery, upstream);
        return sa.run(task, cancelled);
    }
    Tool t = tools.get(node.getToolName());
    Map<String, Object> params = new HashMap<>();
    if (node.getParams() != null) node.getParams().forEach(params::put);
    return t.getExecute().apply(params);
}
```

普通工具节点的调用链是：

```text
Node 里保存：
  toolName = "get_weather"
  params = {"city":"上海"}

invoke(node)：
  Tool t = tools.get("get_weather")
  params = new HashMap<>()
  把 node.params 拷贝进去
  t.getExecute().apply(params)
```

这里的 `apply(params)` 就是真正执行工具函数。

`getExecute()` 拿到的是工具注册时保存的 Java 函数对象，`apply(params)` 是调用这个函数。

## 6. 第三步：synchronized(mu)

```java
synchronized (mu) {
    if (r != null) results.put(nodeId, r);
}
```

先把语法拆开：

```java
private final Object mu = new Object();
```

`mu` 是 `GraphRuntime` 里提前创建好的一个普通 Java 对象。

在这里它不是用来存数据的，而是专门拿来当“锁”。

```java
synchronized (mu) {
    ...
}
```

`synchronized` 是 Java 关键字，意思是：

```text
进入这段代码前，线程必须先拿到 mu 这把锁
拿到锁的线程才能执行大括号里的代码
执行完大括号里的代码后，自动释放 mu 这把锁
```

所以 `synchronized (mu)` 不是调用 `mu` 的方法，也不是执行 `mu()`。

它的真实含义是：

```text
用 mu 作为锁对象，把这一小段代码变成同一时间只能一个线程执行的临界区
```

“临界区”就是多线程里不能让大家同时改的那段代码。

`results` 是图运行时的共享结果表：

```text
results = {
  "n1": "上海：小雨 20°C",
  "n4": "明天适合带伞"
}
```

它的作用是保存已经完成节点的输出。

为什么要 `synchronized (mu)`？

因为同一层的普通节点可能并行跑。

例如：

```text
线程 A：runSingle("n1") 执行完，要写 results
线程 B：runSingle("n4") 执行完，也要写 results
```

如果两个线程同时改同一个 `Map`，可能出现并发问题。

所以代码用同一把锁 `mu`：

```text
线程 A 先拿到 mu 这把锁
  results.put("n1", r1)
  离开 synchronized 代码块，释放 mu

线程 B 再拿到 mu 这把锁
  results.put("n4", r4)
  离开 synchronized 代码块，释放 mu
```

这样 `results` 每次只会被一个线程写。

换成更直白的话：

```text
没有 synchronized:
  多个线程可能同时写 results/errors/graph 状态

有 synchronized(mu):
  大家都排队拿同一把锁
  一次只允许一个线程写共享数据
```

这里为什么不用 `synchronized (results)`？

因为这份运行时里不只保护 `results`，还会保护：

```text
results
errors
graph.nodes 里的 status/result/error
```

用统一的 `mu` 做锁，意思是：这些共享状态的关键写入都按同一把锁排队。

## 7. 为什么 r != null 才写 results

```java
if (r != null) results.put(nodeId, r);
```

`doExecuteNode()` 成功时会返回字符串：

```text
"上海：小雨 20°C"
```

失败、取消、竞速被别人抢先时可能返回：

```java
null
```

普通节点里如果返回 `null`，说明这次没有可用结果，不应该写进 `results`。

失败信息不是写在 `results`，而是写在：

```text
graph.node.error
errors
```

所以这里的规则是：

```text
r != null  -> 这是可传递给下游节点的正常结果，写入 results
r == null  -> 没有正常结果，不写 results
```

还有一个细节：如果工具执行成功但返回值本身是 `null`，`doExecuteNode()` 最后会把它变成空字符串：

```java
return result == null ? "" : result;
```

所以 `runSingle` 看到的成功结果至少是：

```text
""
```

不会是 Java 的 `null`。

## 8. 为什么 sem.release() 放在 finally

```java
try {
    String r = doExecuteNode(nodeId, null);
    synchronized (mu) {
        if (r != null) results.put(nodeId, r);
    }
} finally {
    sem.release();
}
```

`finally` 的意思是：

```text
try 里面正常执行完，会执行 finally
try 里面抛异常，也会执行 finally
try 里面提前 return，也会执行 finally
```

这里必须用 `finally`，因为许可证一定要还。

如果不用 `finally`，可能出现：

```text
n1 acquire() 成功，拿走许可证
n1 执行工具时抛异常
代码没有走到 release()
许可证永远少一张
之后的节点一直卡在 acquire()
```

放在 `finally` 后就变成：

```text
n1 acquire() 成功
n1 执行成功 -> release()
n1 执行失败 -> release()
n1 被中断 -> release()
```

这能保证并发许可不会被某个失败节点“吃掉”。

## 9. 完整例子：n1 调 get_weather

假设图里有一个普通节点：

```text
nodeId = "n1"
node.type = TOOL
node.toolName = "get_weather"
node.params = {"city":"上海"}
node.raceGroup = ""
node.dependsOn = []
```

当前结果表为空：

```text
results = {}
```

执行入口：

```text
execute()
  -> pool.execute(() -> runSingle("n1"))
```

运行细节：

```text
runSingle("n1")

1. acquire()
   sem 许可证数量 -1

2. doExecuteNode("n1", null)
   从 graph.nodes 取到 n1
   推送 nodeStart("n1", "get_weather")
   推送 step(1, node.name)
   推送 toolCall("get_weather", {"city":"上海"})
   graph.setNodeStatus("n1", RUNNING)

   校验 tools.get("get_weather") 是否存在

   invoke(n1)
     Tool t = tools.get("get_weather")
     params = {"city":"上海"}
     t.getExecute().apply(params)
       -> 真正执行 get_weather
       -> 返回 "上海：小雨 20°C"

   graph.setNodeStatus("n1", DONE)
   graph.setNodeResult("n1", "上海：小雨 20°C")
   推送 nodeDone("n1", "get_weather", "done")
   因为 winnerFlag == null
     推送 observation("get_weather", "上海：小雨 20°C")

   return "上海：小雨 20°C"

3. synchronized(mu)
   results.put("n1", "上海：小雨 20°C")

4. finally
   sem.release()
   sem 许可证数量 +1
```

执行后状态：

```text
graph.nodes["n1"].status = DONE
graph.nodes["n1"].result = "上海：小雨 20°C"

results = {
  "n1": "上海：小雨 20°C"
}
```

## 10. 如果工具失败会怎样

假设：

```text
nodeId = "n1"
toolName = "get_weather"
params = {"city":"上海"}
```

但是工具调用一直失败。

运行过程是：

```text
runSingle("n1")
  acquire()
  doExecuteNode("n1", null)
    graph.setNodeStatus("n1", RUNNING)
    invoke(n1) 抛异常
    根据 maxRetries 重试
    仍然失败
    graph.setNodeStatus("n1", FAILED)
    graph.setNodeError("n1", lastErr)
    errors.put("n1", lastErr)
    推送 observation("get_weather", "执行失败: ...")
    return null

  synchronized(mu)
    r == null
    不写 results

  finally
    sem.release()
```

执行后：

```text
results 里没有 "n1"
errors 里有 "n1"
graph.nodes["n1"].status = FAILED
```

所以 `runSingle` 不需要自己再写错误状态。错误状态已经在 `doExecuteNode()` 里写好了。

## 11. runSingle 和 runRace 的区别

`runSingle` 和 `runRace` 都会调用：

```java
doExecuteNode(...)
```

但它们传入的第二个参数不同。

普通节点：

```java
doExecuteNode(nodeId, null)
```

竞速节点：

```java
AtomicBoolean winnerFound = new AtomicBoolean(false);
doExecuteNode(id, winnerFound)
```

区别如下：

| 对比项 | runSingle | runRace |
|---|---|---|
| 节点类型 | 普通节点 | 同一个 `race_group` 里的竞争节点 |
| 是否抢赢家 | 不抢 | 抢 |
| `winnerFlag` | `null` | `AtomicBoolean winnerFound` |
| observation 由谁发 | `doExecuteNode()` 成功后直接发 | `runRace()` 确认赢家后再发 |
| results 由谁写 | `runSingle()` 写 | `runRace()` 只写赢家结果 |
| 失败节点怎么处理 | `doExecuteNode()` 写 FAILED/errors | 失败会进入竞速队列，最后由 `runRace()` 处理 |

为什么竞速节点不能在 `doExecuteNode()` 里直接发 observation？

因为竞速节点里可能有多个工具同时跑：

```text
race_group = "search"

n2: tavily_search
n3: web_search
n5: local_search
```

谁先成功，谁才是赢家。

如果每个节点一成功就发 observation，前端可能看到多个“最终结果”。所以竞速分支要等 `runRace()` 确认赢家后，只发赢家的 observation。

普通节点没有这个问题，所以 `winnerFlag == null` 时，`doExecuteNode()` 直接发 observation。

## 12. 常见误解

### 12.1 runSingle 是不是单线程执行

不是。

`runSingle` 的意思是“执行单个普通节点”，不是“整个图只用一个线程”。

同一层里多个普通节点可以并行：

```text
level = ["n1", "n4", "n7"]

线程 A -> runSingle("n1")
线程 B -> runSingle("n4")
线程 C -> runSingle("n7")
```

真正限制并发数量的是 `Semaphore`：

```text
cfg.maxParallel = 2
```

即使开了 3 个线程，也最多 2 个节点同时通过 `acquire()`。

### 12.2 runSingle 会不会直接调用工具

不会直接调用。

直接调用工具的是 `invoke()`：

```text
runSingle
  -> doExecuteNode
      -> invoke
          -> t.getExecute().apply(params)
```

`runSingle` 是外层壳子，负责并发许可和结果写入。

### 12.3 为什么 results.put 不在 doExecuteNode 里面做

`doExecuteNode()` 负责节点自身状态：

```text
RUNNING / DONE / FAILED / CANCELLED
node.result
node.error
SSE 事件
重试
```

`results` 是 `GraphRuntime` 用来汇总整张图输出的运行时表。

普通节点和竞速节点写 `results` 的规则不同：

```text
普通节点：成功就写自己的结果
竞速节点：只有赢家写结果
```

所以写 `results` 放在外层：

```text
runSingle 负责普通节点的写入规则
runRace 负责竞速节点的写入规则
```

### 12.4 doExecuteNode 返回 null 是不是一定代表工具返回 null

不是。

`doExecuteNode()` 返回 `null` 通常代表：

```text
节点不存在
工具不存在
子 Agent 不存在
执行失败
被取消
竞速分支里别人已经胜出
```

如果工具真的成功执行，但是返回 Java 的 `null`，`doExecuteNode()` 会转换成空字符串：

```java
return result == null ? "" : result;
```

所以对 `runSingle` 来说：

```text
r == null  -> 没有成功结果
r == ""    -> 成功了，只是结果内容为空
```

## 13. 一句话总结

`runSingle(nodeId)` 是普通节点的执行外壳：

```text
拿并发许可
  -> 执行节点 doExecuteNode(nodeId, null)
  -> 成功结果写入 results
  -> 必定释放并发许可
```

真正的工具调用发生在更里面：

```text
doExecuteNode
  -> invoke
      -> t.getExecute().apply(params)
```
