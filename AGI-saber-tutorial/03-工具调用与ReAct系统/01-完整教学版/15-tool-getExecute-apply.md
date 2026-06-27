# 15-tool-getExecute-apply

## 1. 这个方法解决什么问题

前几步做了决策、补了参数，但工具还没真正执行。`tool.getExecute().apply(params)` 就是**真正执行工具的那一步**——把参数传给 execute lambda，拿到原始结果。

它解决的是"从决策到结果"的最后一跳。

## 2. 方法源码，源码里加注释

`tool.getExecute().apply(params)` 不是一个单独的方法文件，而是两次调用组成的链：

```java
// 位置：ToolModeHandler.java:49
//      GraphRuntime.java:448（ReAct 模式下再次出现）
String result = tool.getExecute().apply(tc.getParams());  // ① 取执行函数并传入本次参数
```

拆开看：

```java
// 第一步：getExecute() — 取执行函数
/**
 * 位置：Tool.java:27
 */
public Function<Map<String, Object>, String> getExecute() {
    return execute;  // ① 返回启动时注册的 lambda
}

// 第二步：apply(params) — 调用 lambda
/**
 * 这是 java.util.function.Function 的标准方法
 * 等同于 execute.apply(params)
 */
// Function<Map<String, Object>, String> 的类型签名意味着：
//   输入：Map<String, Object>  ≈  {city: "上海"}
//   输出：String               ≈  "上海：小雨 20°C"
```

### 2.1 `apply` 到底是谁的东西

`apply` 不是 AGI-saber 项目里自己定义的方法。它来自 Java 标准库：

```java
java.util.function.Function<T, R>
```

`Function` 的核心方法就是：

```java
R apply(T t);
```

在本项目里，`Tool.execute` 的类型是：

```java
private transient Function<Map<String, Object>, String> execute;
```

把泛型代进去就是：

```java
String apply(Map<String, Object> params);
```

所以：

```java
tool.getExecute().apply(params)
```

等价于：

```java
Function<Map<String, Object>, String> execute = tool.getExecute();
String result = execute.apply(params);
```

也就是：先拿到工具里保存的执行函数，再把参数 Map 传进去运行。

### 2.2 执行逻辑在哪里定义

执行逻辑不在 `getExecute()` 里，也不在 `apply()` 这个名字里。真正逻辑是在创建 `Tool` 时传进去的 lambda。

位置：`ToolService.java`

```java
private Tool createGetWeatherTool() {
    return new Tool("get_weather", "获取城市天气信息",
            List.of(new ToolParam("city", "string", "城市名称", true)),
            params -> {
                String city = params.get("city") != null
                        ? params.get("city").toString()
                        : "北京";
                String weather = WEATHER_DB.getOrDefault(city, "晴天 20°C（模拟）");
                return city + "：" + weather;
            });
}
```

最后这个 `params -> { ... }` 就是 `execute`。调用 `apply({city:"上海"})` 时，Java 实际执行的就是这个 lambda 的函数体。

完整关系是：

```text
ToolService.createGetWeatherTool()
  → new Tool(..., params -> { 查 WEATHER_DB; return 城市天气; })
  → Tool.execute 保存这个 lambda

ToolModeHandler.run / GraphRuntime.invoke
  → tool.getExecute()
  → 拿出刚才保存的 lambda
  → .apply(params)
  → 执行 lambda 函数体
  → 返回 String 原始结果
```

### 2.3 逐行解释

**① 取执行函数并传入本次参数**

`tool.getExecute()` 先从 `Tool` 对象里取出真正的执行函数。这个函数是在 `ToolService.createGetWeatherTool`、`createGetTimeTool`、`createSearchWebTool` 里注册进去的 lambda。

`apply(tc.getParams())` 再把本次工具调用的参数传进去。这里的 `tc.params` 已经经过前面的两步处理：

```text
ToolService.decide       → 先放入 query 里显式出现的参数
PreferenceFiller.fill    → 再补缺失的偏好参数
```

所以对天气工具来说，执行时看到的通常是 `{city:"上海"}`。如果这个 Map 里没有 `city`，天气工具内部才会走自己的默认值。

## 3. 参数逐个解释

**`apply` 的参数**：`Map<String, Object>`——即 `tc.getParams()`，此时已被 `PreferenceFiller.fill` 补全。

| 工具 | 传入的 params | 取值的 key |
|---|---|---|
| `get_time` | `{}` 或 `{timezone: "Asia/Tokyo"}` | `"timezone"` |
| `get_weather` | `{city: "上海"}` | `"city"` |
| `search_web` | `{query: "..."}` | `"query"` |

## 4. 返回值/副作用解释

**返回值**：`String`——工具的原始执行结果，直接来自 execute lambda 的 return 语句：

```java
// get_time 的 execute lambda return
"2026-06-22 14:30:00"

// get_weather 的 execute lambda return
"上海：小雨 20°C"

// search_web 的 execute lambda return
"AI 应用工程师是将 AI 技术落地到业务的工程师..."

// exec_command 的 execute lambda return
"total 256\ndrwxr-xr-x  12 user  staff  384 Jun 22 14:30 ."
```

**副作用**：取决于具体工具。`get_time` 无副作用（纯读取系统时钟），`exec_command` 有副作用（执行了 shell 命令）。

**异常**：execute lambda 内部可能抛异常。在 `ToolModeHandler.run` 中：

```java
try {
    String result = tool.getExecute().apply(tc.getParams());  // 可能抛异常
    tc.setToolResult(result);
} catch (Exception e) {
    resp.setAnswer("工具执行失败: " + e.getMessage());
    resp.setToolCall(tc);
    return;  // 不再继续，不调 LLM 总结
}
```

## 5. 这一步在完整链路中的位置

```text
ToolModeHandler.run
  ├── ① toolService.decide(query, ts) → ToolCallResult
  ├── ② ts.get(tc.getToolName()) → Tool 对象
  ├── ③ PreferenceFiller.fill(tc, pref) → 补全 params
  ├── ④ tool.getExecute().apply(tc.getParams()) → "上海：小雨 20°C" ← 你在这里
  └── ⑤ llm.chat(sp, userMsg) → 自然语言回答
```

在 ReAct 模式下，`apply` 再次出现：

```text
GraphRuntime.invoke(Node node)
  → if TOOL:
      Tool t = tools.get(node.toolName)
      t.getExecute().apply(params)  ← 和 ToolModeHandler 里完全一样的调用
```

## 6. 用"上海天气怎么样？"跑一遍

```text
① decide: tc.params = {city: "上海"}（从 query 提取）
③ fill:    city 已有值，不覆盖，tc.params 不变
④ apply({city: "上海"}):

进入 createGetWeatherTool 的 execute lambda：

   String city = params.get("city") != null          // "上海"
       ? params.get("city").toString()               // "上海"
       : "北京";                                     // 不会走到这里

   String weather = WEATHER_DB.getOrDefault("上海",  // WEATHER_DB 有上海
                    "晴天 20°C（模拟）");              // 不会走到 fallback
   // → "小雨 20°C"

   return "上海" + "：" + "小雨 20°C";
   // → "上海：小雨 20°C"

⑤ toolResult = "上海：小雨 20°C"
   tc.setToolResult("上海：小雨 20°C")

⑥ llm.chat:
   输入："用户问：上海天气怎么样？\n工具 get_weather 返回结果：上海：小雨 20°C"
   输出："上海目前小雨，约 20°C，出门建议带伞。"
```

## 7. 常见误解

**误解一："apply 等于调用一个 HTTP API"**

对 `search_web` 的真实版本（Tavily），是的。对 `get_time`，只是 `ZonedDateTime.now()`。`apply` 屏蔽了实现细节——调用方不关心工具内部是读系统时钟还是发 HTTP 请求。

**误解二："apply 的结果就是最终回答"**

不是。`apply` 返回的是**原始数据**（如 `"上海：小雨 20°C"`），最终回答是 LLM 基于它生成的（如 `"上海目前小雨，约 20°C，出门建议带伞。"`）。`apply` 不返回 markdown，不返回自然语言，只返回结构化或半结构化的数据。

**误解三："Function<Map<String, Object>, String> 中的 String 返回值限制太大了"**

所有工具的原始结果统一为 `String`，这是一个刻意的简化。工具可以内部生成 JSON 字符串，LLM 可以解析它。如果某个工具需要返回二进制（如图片），当前架构不支持——需要扩展 `execute` 的类型签名。

**误解四："apply 只在 ToolModeHandler 里被调用"**

在 ReAct 模式下也被调用——`GraphRuntime.invoke` 对每个 TOOL 节点执行 `t.getExecute().apply(params)`。两个模式共享完全相同的工具执行路径。

**误解五："apply 抛出的异常应该由工具自己处理"**

当前架构中，工具内部的异常被 `ToolModeHandler.run` 的 try-catch 捕获，然后返回"工具执行失败: ..."。工具也可以自己 catch 所有异常并返回错误字符串（如 `get_time` 的 `catch (Exception ignored) {}`）。这是两层的容错：工具内部容错 + 外层兜底。

**误解六："params 的类型是 Map<String, Object>，所以可以传非字符串参数"**

是的，类型签名允许。但当前所有工具都只读 `String` 类型的值（`params.get("city").toString()`）。如果将来需要传数字（如 `{count: 5}`），需要工具内部做 `Integer.parseInt` 或 `instanceof` 检查。
