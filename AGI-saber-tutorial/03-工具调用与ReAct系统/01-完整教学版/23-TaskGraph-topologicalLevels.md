# 23-TaskGraph-topologicalLevels

## 1. 这个方法解决什么问题

`TaskGraph` 构造方法已经把 `List<Node>` 变成了三张表：

```text
nodes    = 所有节点
adjList  = 上游 -> 下游
inDegree = 每个节点还要等几个上游
```

但 `GraphRuntime` 执行时还需要知道：

```text
第 1 批可以执行哪些节点？
第 2 批可以执行哪些节点？
哪些节点可以放在同一层并行执行？
图里有没有循环依赖？
```

`topologicalLevels()` 就是解决这个问题的。它用 Kahn 算法把 DAG 分成一层一层：

```text
[
  ["n1"],
  ["n2", "n3"],
  ["n4"]
]
```

含义是：

```text
L0: n1 先执行
L1: n1 完成后，n2 和 n3 可以并行执行
L2: n2 和 n3 都完成后，n4 才能执行
```

这个方法只计算层级，不真正执行工具。

## 2. Kahn 算法在这里怎么理解

不用先背算法名，先看规则：

```text
1. 找出当前入度为 0 的节点
2. 这些节点组成当前层 level
3. 假装这些节点已经执行完
4. 把它们的下游节点入度减 1
5. 重复上面过程，直到找不到入度为 0 的节点
6. 如果还有节点没处理完，说明图里有环
```

入度为 0 的意思是：

```text
这个节点没有未完成的上游依赖，现在可以执行
```

为什么同层节点可以并行？因为它们都是同一轮里找到的 `入度=0` 节点，彼此之间没有“谁必须等谁”的关系。

## 3. 方法源码

```java
/**
 * 位置：TaskGraph.java:52-78
 */
public List<List<String>> topologicalLevels() {
    if (levelsCache != null) return levelsCache;                    // ① 已算过就直接返回缓存

    Map<String, Integer> inDeg = new LinkedHashMap<>(inDegree);     // ② 拷贝入度表
    List<List<String>> levels = new ArrayList<>();                  // ③ 保存最终层级
    int processed = 0;                                              // ④ 已处理节点数

    while (true) {
        List<String> ready = new ArrayList<>();                     // ⑤ 当前这一层的节点
        for (Map.Entry<String, Integer> e : inDeg.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());           // ⑥ 收集入度为 0 的节点
        }

        if (ready.isEmpty()) break;                                 // ⑦ 没有可执行节点，退出循环

        levels.add(ready);                                          // ⑧ 当前层加入结果
        processed += ready.size();                                  // ⑨ 累加已处理数量

        for (String id : ready) {
            inDeg.put(id, -1);                                      // ⑩ 当前节点标记为已处理
            for (String down : adjList.getOrDefault(id, Collections.emptyList())) {
                inDeg.merge(down, -1, Integer::sum);                // ⑪ 下游节点入度减 1
            }
        }
    }

    if (processed != nodes.size()) {                                // ⑫ 还有节点没处理完，说明有环
        throw new IllegalStateException(
                "task graph has cycle: processed " + processed + "/" + nodes.size() + " nodes");
    }

    levelsCache = levels;                                           // ⑬ 缓存层级结果
    return levels;
}
```

## 4. 逐行解释

**① `if (levelsCache != null) return levelsCache`**

拓扑层级算出来后会缓存在 `levelsCache`。同一张图多次调用 `topologicalLevels()` 时，不需要重复算。

例如：

```text
第一次调用：计算出 [[n1], [n2, n3], [n4]]
第二次调用：直接返回缓存
```

**② `Map<String, Integer> inDeg = new LinkedHashMap<>(inDegree)`**

这里拷贝了一份入度表。

原因是拓扑排序过程中会不断修改入度：

```text
n1 执行完 -> n2 入度减 1
n1 执行完 -> n3 入度减 1
```

这些修改只是为了计算层级，不应该破坏 `TaskGraph` 原始的 `inDegree`。所以代码改的是临时变量 `inDeg`，不是成员变量 `inDegree`。

**③ `List<List<String>> levels = new ArrayList<>()`**

`levels` 保存最终结果。

结构是：

```text
levels[0] = 第一层节点
levels[1] = 第二层节点
levels[2] = 第三层节点
```

每一层是一个 `List<String>`，里面放的是节点 id，不是 `Node` 对象。

**④ `int processed = 0`**

`processed` 记录已经放进层级结果的节点数量。

它的用途是最后判断有没有环：

```text
processed == nodes.size()  所有节点都处理完，无环
processed < nodes.size()   有节点永远等不到入度变 0，说明有环
```

**⑤ `List<String> ready = new ArrayList<>()`**

`ready` 是当前这一轮找到的可执行节点，也就是当前层。

比如某一轮找到：

```text
ready = ["n2", "n3"]
```

说明 `n2` 和 `n3` 属于同一层；到执行阶段，`GraphRuntime` 可以把同一层节点并行处理。

**⑥ `if (e.getValue() == 0) ready.add(e.getKey())`**

遍历临时入度表，把入度为 0 的节点放进 `ready`。

```text
inDeg:
  n1 -> 0
  n2 -> 1
  n3 -> 1

ready = [n1]
```

入度为 0 表示没有未满足依赖，所以可以进入当前层。

**⑦ `if (ready.isEmpty()) break`**

如果这一轮找不到入度为 0 的节点，循环结束。

这有两种可能：

```text
情况 1：所有节点都处理完了，正常结束
情况 2：还有节点没处理完，但它们互相等待，图里有环
```

到底是哪一种，要等循环结束后用 `processed != nodes.size()` 判断。

**⑧ `levels.add(ready)`**

把当前层加入最终结果。

例如：

```text
ready = [n1]
levels = [[n1]]
```

下一轮如果：

```text
ready = [n2, n3]
levels = [[n1], [n2, n3]]
```

**⑨ `processed += ready.size()`**

当前层有几个节点，就把已处理数量加几。

```text
ready = [n2, n3]
processed += 2
```

**⑩ `inDeg.put(id, -1)`**

把当前层的节点标记为已处理。

为什么用 `-1`？因为下一轮扫描 `inDeg` 时只收集 `0`：

```java
if (e.getValue() == 0) ready.add(e.getKey());
```

已经处理过的节点如果不改成 `-1`，下一轮还会再次被收集。

**⑪ `inDeg.merge(down, -1, Integer::sum)`**

当前节点执行完后，它的下游节点少等一个上游，所以入度减 1。

如果：

```text
adjList["n1"] = ["n2", "n3"]
```

处理 `n1` 时：

```text
inDeg["n2"] 从 1 变 0
inDeg["n3"] 从 1 变 0
```

这样下一轮 `n2` 和 `n3` 就会进入 `ready`。

**⑫ `if (processed != nodes.size())`**

循环结束后，如果处理过的节点数小于总节点数，说明有节点永远没有变成入度 0。

这通常就是循环依赖：

```text
n1 dependsOn=[n2]
n2 dependsOn=[n1]
```

两边都在等对方，谁都不会先变成入度 0。

**⑬ `levelsCache = levels`**

把计算结果缓存起来，然后返回。

## 5. 完整例子

用这张图：

```text
        n1 查询天气
       /  \
      ↓    ↓
   n2 搜索  n3 查知识库
      \    /
       ↓  ↓
      n4 生成建议
```

构造函数得到：

```text
adjList:
  n1 -> [n2, n3]
  n2 -> [n4]
  n3 -> [n4]
  n4 -> []

inDegree:
  n1 -> 0
  n2 -> 1
  n3 -> 1
  n4 -> 2
```

`topologicalLevels()` 先拷贝：

```text
inDeg = {n1:0, n2:1, n3:1, n4:2}
levels = []
processed = 0
```

### Round 1

扫描 `inDeg`：

```text
n1 入度 0 -> ready 加入 n1
n2 入度 1 -> 不加入
n3 入度 1 -> 不加入
n4 入度 2 -> 不加入
```

得到：

```text
ready = [n1]
levels = [[n1]]
processed = 1
```

处理 `n1`：

```text
inDeg["n1"] = -1
n1 的下游是 [n2, n3]
inDeg["n2"] 从 1 减到 0
inDeg["n3"] 从 1 减到 0
```

本轮结束：

```text
inDeg = {n1:-1, n2:0, n3:0, n4:2}
```

### Round 2

扫描 `inDeg`：

```text
n2 入度 0 -> ready 加入 n2
n3 入度 0 -> ready 加入 n3
```

得到：

```text
ready = [n2, n3]
levels = [[n1], [n2, n3]]
processed = 3
```

处理 `n2`：

```text
inDeg["n2"] = -1
n2 的下游是 [n4]
inDeg["n4"] 从 2 减到 1
```

处理 `n3`：

```text
inDeg["n3"] = -1
n3 的下游是 [n4]
inDeg["n4"] 从 1 减到 0
```

本轮结束：

```text
inDeg = {n1:-1, n2:-1, n3:-1, n4:0}
```

### Round 3

扫描 `inDeg`：

```text
n4 入度 0 -> ready 加入 n4
```

得到：

```text
ready = [n4]
levels = [[n1], [n2, n3], [n4]]
processed = 4
```

处理 `n4`：

```text
inDeg["n4"] = -1
n4 没有下游
```

本轮结束：

```text
inDeg = {n1:-1, n2:-1, n3:-1, n4:-1}
```

### Round 4

扫描 `inDeg`，没有任何节点入度为 0：

```text
ready = []
break
```

最后检查：

```text
processed = 4
nodes.size() = 4
```

两者相等，说明无环，返回：

```text
[[n1], [n2, n3], [n4]]
```

## 6. 有环时怎么发现

假设图是：

```text
n1.dependsOn = ["n2"]
n2.dependsOn = ["n1"]
```

构造出来：

```text
inDeg = {n1:1, n2:1}
```

第一轮扫描：

```text
n1 入度 1，不可执行
n2 入度 1，不可执行
ready = []
```

循环直接结束。

此时：

```text
processed = 0
nodes.size() = 2
```

`processed != nodes.size()`，所以抛异常：

```text
task graph has cycle: processed 0/2 nodes
```

这个异常会被 `ReActLoop.runStream` 捕获，然后把所有 `dependsOn` 清空，退化成全并行执行。

## 7. 参数、返回值和副作用

**参数**：无。它读取 `TaskGraph` 内部的 `inDegree / adjList / nodes`。

**返回值**：`List<List<String>>`。

```text
外层 List = 所有层
内层 List = 某一层的节点 id
```

例如：

```text
[[n1], [n2, n3], [n4]]
```

**副作用**：

```text
会写 levelsCache
不会修改原始 inDegree
不会执行节点
不会修改 Node.status/result/error
```

## 8. 位置

```text
ReActLoop.runStream
  ├── new TaskGraph(nodes)
  ├── tg.validate()
  │     └── topologicalLevels()  检查有没有环
  └── GraphRuntime.execute()
        └── graph.topologicalLevels()  拿到执行层级
```

`GraphRuntime.execute()` 拿到层级后，会按层执行：

```text
L0: [n1]
L1: [n2, n3]
L2: [n4]
```

层与层之间串行，同一层内部可以并行或竞速。

## 9. 常见误解

**误解一：`topologicalLevels()` 会执行工具**

不会。它只返回层级列表。真正执行在 `GraphRuntime.execute()`。

**误解二：`inDeg` 和 `inDegree` 是同一个东西**

不是。`inDeg` 是局部拷贝，专门给本次拓扑排序修改用。`inDegree` 是图对象里的原始入度表。

**误解三：`ready.isEmpty()` 就一定表示有环**

不一定。也可能是所有节点都处理完了。必须看：

```text
processed == nodes.size()
```

如果相等，正常结束；如果不相等，才是有环。

**误解四：同层节点一定按顺序执行**

不一定。同层只表示它们没有依赖关系，可以并行。实际执行顺序由 `GraphRuntime` 的线程池和竞速逻辑决定。
