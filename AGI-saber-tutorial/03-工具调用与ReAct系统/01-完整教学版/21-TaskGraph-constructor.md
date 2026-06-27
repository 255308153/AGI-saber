# 21-TaskGraph-constructor

## 1. 这个方法解决什么问题

`Planner.planGraph` 返回的是 `List<Node>`。它只是一个普通列表：

```text
[n1, n2, n3, n4]
```

但这些节点不是一定按列表顺序执行。每个 `Node` 里还有 `dependsOn`：

```text
n2.dependsOn = ["n1"]
n3.dependsOn = ["n1"]
n4.dependsOn = ["n2", "n3"]
```

这表示：

```text
n2 要等 n1
n3 要等 n1
n4 要等 n2 和 n3
```

`TaskGraph` 构造方法的作用，就是把这种扁平 `List<Node>` 变成执行器能看懂的图结构：

```text
nodes    = 所有节点，方便通过 id 找 Node
adjList  = 上游 -> 下游，方便知道一个节点完成后影响谁
inDegree = 每个节点还剩几个依赖没完成
```

构造方法只建图，不执行工具，也不检查环。真正校验在第 22 章 `validate()`，真正分层在第 23 章 `topologicalLevels()`。

## 2. DAG 是什么

DAG 是 `Directed Acyclic Graph`，中文叫**有向无环图**。

在本项目里可以这样理解：

```text
Graph     = 一堆任务节点 + 节点之间的依赖关系
Directed  = 依赖有方向，n1 -> n2 表示 n1 完成后 n2 才能跑
Acyclic   = 不能有环，不能 n1 等 n2、n2 又等 n1
```

例如：

```text
        n1 查询天气
       /  \
      ↓    ↓
   n2 搜索  n3 查知识库
      \    /
       ↓  ↓
      n4 生成建议
```

这张图里：

```text
n1 -> n2
n1 -> n3
n2 -> n4
n3 -> n4
```

所以：

```text
n1 入度 0，可以先跑
n2 入度 1，要等 n1
n3 入度 1，要等 n1
n4 入度 2，要等 n2 和 n3
```

代码里没有一个单独叫 `DAG` 的类。当前项目是用 `TaskGraph` 里的三张表表达 DAG：

```java
private final Map<String, Node> nodes = new LinkedHashMap<>();
private final Map<String, List<String>> adjList = new LinkedHashMap<>();
private final Map<String, Integer> inDegree = new LinkedHashMap<>();
```

## 3. 方法源码

```java
/**
 * 位置：TaskGraph.java:27-42
 */
public TaskGraph(List<Node> all) {
    if (all == null) return;

    // 第一轮：先登记所有节点
    for (Node n : all) {
        n.setStatus(NodeStatus.PENDING);             // ① 状态重置为待执行
        nodes.put(n.getId(), n);                     // ② id -> Node
        adjList.put(n.getId(), new ArrayList<>());   // ③ 先准备空下游列表
        inDegree.put(n.getId(), 0);                  // ④ 先假设入度为 0
    }

    // 第二轮：根据 dependsOn 建边，并计算入度
    for (Node n : all) {
        for (String dep : n.getDependsOn()) {        // ⑤ dep 是 n 的上游
            if (!nodes.containsKey(dep)) continue;   // ⑥ 构造阶段先跳过悬空依赖
            adjList.get(dep).add(n.getId());         // ⑦ 建边：dep -> n
            inDegree.merge(n.getId(), 1, Integer::sum); // ⑧ n 的入度 +1
        }
    }
}
```

## 4. 逐行解释

**① `n.setStatus(NodeStatus.PENDING)`**

把每个节点状态重置成 `PENDING`。构造图时还没有执行任何节点，所以所有节点都应该是“待执行”。

**② `nodes.put(n.getId(), n)`**

把节点放进 `nodes` 表。之后只要知道 `"n1"`，就能拿到对应的 `Node`。

```text
nodes:
  n1 -> Node(get_weather)
  n2 -> Node(search_web)
```

**③ `adjList.put(n.getId(), new ArrayList<>())`**

给每个节点准备一个空的下游列表。

一开始还没处理依赖，所以每个节点的下游都是空：

```text
adjList:
  n1 -> []
  n2 -> []
  n3 -> []
```

第二轮如果发现 `n2.dependsOn=["n1"]`，就会把 `n2` 加到 `n1` 的下游列表里。

**④ `inDegree.put(n.getId(), 0)`**

先把每个节点入度设为 0。

入度的意思是：这个节点还要等几个上游节点完成。

```text
inDegree = 0  可以执行
inDegree > 0 还要等
```

**⑤ `for (String dep : n.getDependsOn())`**

遍历当前节点的所有上游依赖。

如果：

```text
n4.dependsOn = ["n2", "n3"]
```

就会依次处理：

```text
dep = n2
dep = n3
```

**⑥ `if (!nodes.containsKey(dep)) continue`**

如果 `dependsOn` 里写了不存在的节点，构造函数先跳过，避免空指针。

注意：这不是认为它合法。严格检查在 `validate()` 里做，那里会抛异常。

**⑦ `adjList.get(dep).add(n.getId())`**

这一步把“依赖关系”翻译成“下游列表”。

如果：

```text
n2.dependsOn = ["n1"]
```

意思是：

```text
n1 -> n2
```

所以写入：

```text
adjList["n1"].add("n2")
```

结果：

```text
adjList:
  n1 -> [n2]
  n2 -> []
```

**⑧ `inDegree.merge(n.getId(), 1, Integer::sum)`**

当前节点 `n` 多了一个上游依赖，所以入度加 1。

如果：

```text
n4.dependsOn = ["n2", "n3"]
```

会加两次：

```text
处理 n2 -> inDegree["n4"] = 1
处理 n3 -> inDegree["n4"] = 2
```

这表示 `n4` 必须等两个上游都完成后才能执行。

## 5. 完整例子

输入节点：

```text
n1.dependsOn = []
n2.dependsOn = ["n1"]
n3.dependsOn = ["n1"]
n4.dependsOn = ["n2", "n3"]
```

第一轮循环后：

```text
nodes:
  n1 -> Node(n1)
  n2 -> Node(n2)
  n3 -> Node(n3)
  n4 -> Node(n4)

adjList:
  n1 -> []
  n2 -> []
  n3 -> []
  n4 -> []

inDegree:
  n1 -> 0
  n2 -> 0
  n3 -> 0
  n4 -> 0
```

第二轮处理依赖：

```text
n2.dependsOn=["n1"]
  -> adjList["n1"].add("n2")
  -> inDegree["n2"] += 1

n3.dependsOn=["n1"]
  -> adjList["n1"].add("n3")
  -> inDegree["n3"] += 1

n4.dependsOn=["n2","n3"]
  -> adjList["n2"].add("n4")
  -> inDegree["n4"] += 1
  -> adjList["n3"].add("n4")
  -> inDegree["n4"] += 1
```

最终结果：

```text
nodes:
  n1 -> Node(n1)
  n2 -> Node(n2)
  n3 -> Node(n3)
  n4 -> Node(n4)

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

这就说明：

```text
n1 可以先执行
n2、n3 要等 n1
n4 要等 n2 和 n3
```

## 6. 位置

```text
ReActLoop.runStream
  ├── planner.planGraph → List<Node>
  ├── new TaskGraph(nodes)  ← 你在这里
  ├── tg.validate()
  └── rt.execute()
```

## 7. 常见误解

**误解一：`adjList` 是入边表**

不是。`adjList` 是出边表。

```text
adjList["n1"] = ["n2", "n3"]
```

意思是 `n1` 完成后会影响 `n2` 和 `n3`。

**误解二：构造函数会检查环**

不会。构造函数只建三张表。循环依赖在 `validate()` 调 `topologicalLevels()` 时才会被发现。

**误解三：入度是固定不变的**

构造完成时，`inDegree` 表示初始依赖数。执行时，每完成一个节点，它的下游节点入度会减 1。入度减到 0，节点才变成可执行。

**误解四：悬空依赖在构造函数里就报错**

不会。构造函数跳过不存在的 dep，严格报错在 `validate()`。
