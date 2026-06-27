# 00-工具调用与ReAct学习路线

## 1. 一句话结论

这套工具系统按**一次请求真实调用的方法顺序**往下学。从 `processInternal` 进入，沿 `decideMode` → `ToolModeHandler.run`（单工具）或 `ReActLoop.runStream`（ReAct）一路追到 `llm.chat` 或 `ChatGenerator.generate` 的最终回答。

每章对着一个具体方法，按固定模板拆解：解决什么问题 → 源码注解 → 参数 → 返回值 → 链路位置 → 实例走查 → 常见误解。

## 2. 本套教程怎么读

分成四段，沿调用链顺序。

**Phase 1：入口与路由（第 1–11 章）**

```text
01 processInternal        — 一轮请求从哪里进入工具系统
02 decideMode             — 模式决策总入口
03 needTool               — 单工具触发规则
04 needReAct              — 多步骤任务识别
05 needRAG                — RAG 为什么排在 tool/react 后面
06 filterTools            — 前端的工具选择怎么裁剪
07 getDefaultTools        — 默认工具库怎么创建
08 createGetTimeTool      — 一个工具对象怎么写（可选参数）
09 createGetWeatherTool   — 必填参数，mock DB（必填参数）
10 createSearchWebTool    — mock 搜索，真实替换（整句参数）
11 Tool/ToolParam/ToolCallResult — 三个数据类的边界
```

**Phase 2：单工具链（第 12–16 章）**

```text
12 ToolModeHandler.run    — 单工具模式完整五步主流程
13 ToolService.decide     — 从 query 决策工具名和参数
14 PreferenceFiller.fill  — 偏好补参，不覆盖用户显式输入
15 tool.getExecute.apply  — 真正执行工具，拿到原始结果
16 ChatGenerator.generate — 多 observation 综合成自然语言
```

**Phase 3：ReAct 链（第 17–31 章）**

```text
17 ReActLoop.runStream    — ReAct 模式五步总入口
18 Planner.planGraph      — LLM 规划任务图
19 Planner.rulePlanNodes  — 规则降级规划
20 Node                   — 任务节点数据载体
21 TaskGraph 构造          — 从 List<Node> 到 DAG
22 TaskGraph.validate     — 循环依赖和悬空引用检查
23 topologicalLevels      — 拓扑分层，哪些节点可并行
24 GraphRuntime.execute   — 图执行总控
25 groupByRace            — 按竞速组分组
26 runSingle              — 普通节点执行
27 runRace                — 竞速 first-success-wins
28 doExecuteNode          — 单节点状态机
29 invoke                 — 工具 vs 子Agent 分发
30 successfulResults      — 有效 observation 筛选
31 buildResult            — 整理 GraphResult
```

**Phase 4：综合与总结（第 32–34 章）**

```text
32 完整例子一：上海天气怎么样     — 单工具全链路走查
33 完整例子二：天气+搜索建议      — ReAct 全链路走查
34 面试总结                      — 按方法链叙述
```

## 3. 每章固定模板

```text
1. 这个方法解决什么问题
2. 方法源码，源码里加注释
3. 参数逐个解释
4. 返回值/副作用解释
5. 这一步在完整链路中的位置
6. 用"上海天气怎么样"或"天气+搜索建议"跑一遍
7. 常见误解
```

## 4. 源码位置

```text
主入口：
  UnifiedAgentService.java       — processInternal, filterTools
  ChatRouter.java                — decideMode, needTool, needReAct, needRAG

单工具链路：
  ToolService.java               — getDefaultTools, createGetTimeTool,
                                   createGetWeatherTool, createSearchWebTool, decide
  ToolModeHandler.java           — run
  PreferenceFiller.java          — fill
  ChatGenerator.java             — generate

数据类：
  Tool.java, ToolParam.java, ToolCallResult.java

ReAct 链路：
  ReActLoop.java                 — runStream
  Planner.java                   — planGraph, rulePlanNodes
  Node.java                      — 数据类
  TaskGraph.java                 — 构造, validate, topologicalLevels, successfulResults
  GraphRuntime.java              — execute, groupByRace, runSingle, runRace,
                                   doExecuteNode, invoke, buildResult
```
