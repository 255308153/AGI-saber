# 07-ToolService-getDefaultTools

## 1. 这个方法解决什么问题

系统启动时，工具体系需要一个**初始工具集**。`getDefaultTools` 创建并返回三个内置工具——`get_time`、`get_weather`、`search_web`——它们构成了工具系统的最底层能力。

这三个工具用 mock 实现，但在架构上占的位置和真实工具完全一样：它们都注册在同一个 `Map<String, Tool>` 里，通过相同的 `execute.apply(params)` 接口调用。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ToolService.java:30-36
 *
 * 参数：无
 * 返回：包含三个默认工具的 ConcurrentHashMap
 */
public Map<String, Tool> getDefaultTools() {
    Map<String, Tool> tools = new ConcurrentHashMap<>();        // ① 线程安全的 Map
    tools.put("get_time", createGetTimeTool());                // ② 时间工具
    tools.put("get_weather", createGetWeatherTool());          // ③ 天气工具
    tools.put("search_web", createSearchWebTool());            // ④ 搜索工具
    return tools;                                              // ⑤ 返回
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 线程安全的 Map**

线程安全的 Map。工具库会在服务初始化和运行时被读取，使用线程安全 Map 可以降低并发读写时的数据竞争风险。

**② 时间工具**

时间工具。这里把 `get_time` 放进默认工具库，后续 `ToolService.decide` 才能通过 `tools.containsKey("get_time")` 判断它可用。

**③ 天气工具**

天气工具。这里注册 `get_weather`，它的 key 必须和 `decide` 里返回的 `toolName` 完全一致。

**④ 搜索工具**

搜索工具。这里注册 `search_web`，搜索类 query 后续会通过这个 key 找到对应 Tool 对象。

**⑤ 返回**

返回。这一行会直接结束当前方法或结束当前分支，所以它决定了调用方下一步能拿到什么值。


## 3. 参数逐个解释

没有参数。它是无参工厂方法。

## 4. 返回值/副作用解释

**返回值**：`Map<String, Tool>`——key 是工具名，value 是工具对象。

**为什么是 `ConcurrentHashMap`？**

因为 `tools` 在 `UnifiedAgentService.init()` 中会被合并到全局工具库：

```java
// UnifiedAgentService.java:127
tools.putAll(toolService.getDefaultTools());
```

之后全局 `tools` 会在多线程环境下被读取（每个请求都可能查询工具库）。`ConcurrentHashMap` 保证并发读安全。

**返回的三个工具**：

| key | Tool.name | 参数 | 执行方式 |
|---|---|---|---|
| `get_time` | `"获取当前时间"` | `timezone`（可选） | Java `ZonedDateTime` |
| `get_weather` | `"获取城市天气信息"` | `city`（必填） | 内存 `WEATHER_DB` 查表 |
| `search_web` | `"搜索互联网获取最新信息"` | `query`（必填） | mock 关键词匹配 |

## 5. 这一步在完整链路中的位置

`getDefaultTools` 不是在每次请求中调用的。它在**系统启动**时调用一次：

```text
UnifiedAgentService.init()                      ← @PostConstruct，启动时执行
  ├── tools.putAll(toolService.getDefaultTools())  ← 你在这里
  ├── tools.put("rag_search", ...)                 ← 额外注册的 RAG 工具
  ├── tools.put("search_web", ...)                 ← 覆盖为 Tavily+LLM 实现
  ├── tools.put("exec_command", ...)               ← 可选的沙箱工具
  └── ...
```

注意第 163 行 `search_web` 被**覆盖**了：

```java
// 第 127 行：先注册 mock 版
tools.putAll(toolService.getDefaultTools());
// ...
// 第 163 行：再覆盖为真实版（Tavily + LLM fallback）
tools.put("search_web", new Tool("search_web", "搜索互联网获取最新信息", ...));
```

这意味着 `getDefaultTools` 提供的 `search_web` 只是一个**初始占位**。如果 `search_web` 后续被覆盖（取决于配置），实际使用的是 Tavily 客户端版本。

## 6. 用"上海天气怎么样？"跑一遍

`getDefaultTools` 在启动时执行，不参与单次请求。但它在启动时创建了 `get_weather` 工具，这就是"上海天气怎么样？"最终调用的那一个：

```text
启动时：
  getDefaultTools()
    → tools = {get_time, get_weather, search_web}
    → 合并到全局 tools

请求时：
  processInternal
    → toolset = tools（包含 get_weather）
    → ToolModeHandler.run
      → ToolService.decide → ToolCallResult(toolName="get_weather")
      → ts.get("get_weather") → 就是 getDefaultTools 创建的那个 Tool 对象
      → tool.getExecute().apply({city: "上海"}) → "上海：小雨 20°C"
```

如果没有 `getDefaultTools` 在启动时创建并注册 `get_weather`，`ts.get("get_weather")` 会返回 `null`，请求会报错"工具 get_weather 不存在"。

## 7. 常见误解

**误解一："getDefaultTools 在每次请求时调用"**

不会。它只在 `@PostConstruct init()` 中调用一次。后续所有请求共享同一批 Tool 对象。

**误解二："getDefaultTools 的返回值就是最终的工具库"**

不是。它只是初始集。`UnifiedAgentService.init()` 会继续往 `tools` 里加 `rag_search`、覆盖 `search_web`、可选加 `exec_command`。最终的工具库是多次 `put` 累积的结果。

**误解三："ConcurrentHashMap 意味着工具可以热加载"**

当前实现中，所有工具都在 `init()` 中一次性注册。`ConcurrentHashMap` 选型是为并发读安全，不是为了支持运行时热加载。但要实现热加载（运行时 `registerTool`），`ConcurrentHashMap` 确实提供了基础——`put` 操作线程安全。

**误解四："三个默认工具都是用 mock 实现的，所以它们不是'真正的'工具"**

从架构角度看，它们和真实工具完全等价。它们有相同的 `Tool` 类型、相同的 `execute` 接口、相同的 `apply(params)` 签名。是否 mock 只是 `execute` lambda 内部的实现细节。替换 mock 为真实 API，不需要改 `ToolService.decide`、`ToolModeHandler.run`、`PreferenceFiller.fill` 中的任何一行。
