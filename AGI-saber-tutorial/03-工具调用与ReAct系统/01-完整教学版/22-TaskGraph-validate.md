# 22-TaskGraph-validate

## 1. 这个方法解决什么问题

图构造好了，但节点可能有悬空依赖（`dependsOn` 指向不存在的节点）或循环依赖（A 依赖 B，B 依赖 A）。`validate` 在任务执行前做这两项检查，失败抛异常。

## 2. 方法源码

```java
/**
 * 位置：TaskGraph.java:81-91
 */
public void validate() {
    // 检查 1：悬空依赖
    for (Node n : nodes.values()) {
        for (String dep : n.getDependsOn()) {
            if (!nodes.containsKey(dep)) {                       // ① dep 不存在于图中
                throw new IllegalStateException(
                    "node " + n.getId() + " depends on nonexistent node " + dep);
            }
        }
    }
    // 检查 2：循环依赖（通过调拓扑排序检测）
    topologicalLevels();                                         // ② 内部有环时抛异常
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① dep 不存在于图中**

dep 不存在于图中。这里是保护条件，目的是避免后面的执行逻辑拿到空对象、错误工具或不满足条件的数据。

**② 内部有环时抛异常**

内部有环时抛异常。这是拓扑排序的关键状态变化，用来判断哪些节点可以并行执行、哪些节点必须等待上游完成。


## 3. 参数/返回值

无参数（读内部状态）。无返回值（void）。校验通过无异常，不通过抛 `IllegalStateException`。

## 4. 两种校验失败的修复

```text
悬空依赖 → IllegalStateException → ReActLoop 不触发 catch（这是编程错误，不应修复）
循环依赖 → topologicalLevels 抛异常 → ReActLoop catch → 清除所有 dependsOn → 重建全并行图
```

**注意**：`ReActLoop.runStream` 的 catch 块只清除 `dependsOn` 再重建——这是对"循环依赖"的降级策略。对"悬空依赖"只记录日志然后也降级——但悬空依赖说明规划有问题，降级后仍可能丢失正确的依赖关系。

## 5. 位置

```text
new TaskGraph(nodes)
  → tg.validate()  ← 你在这里
  → (失败 → clear dependsOn → 重建)
  → rt.execute()
```

## 6. 用例子跑一遍

```text
正常图（n2 依赖 n1）：
  validate()
    → 检查悬空：n2.dependsOn=["n1"]，n1 存在 ✓
    → topologicalLevels(): 无环 ✓
    → 通过

异常图（循环依赖）：
  n1.dependsOn=["n2"]
  n2.dependsOn=["n1"]
  validate()
    → 检查悬空：n1存在，n2存在 ✓
    → topologicalLevels():
       Kahn算法：processed=0 < 2
       → throw "task graph has cycle: processed 0/2 nodes"
    → ReActLoop catch → clear all dependsOn → 重建
```

## 7. 常见误解

**误解一："validate 在构造方法中自动调用"**

不是。构造方法和 validate 是分开的。`ReActLoop` 里显式调用 `tg.validate()`。

**误解二："悬空依赖和循环依赖都是被清除 dependsOn 后重试的"**

是的，当前实现中两者都在同一个 catch 块处理。但悬空依赖是更严重的问题——Planner 引用了不存在的工具。
