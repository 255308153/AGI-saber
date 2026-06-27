# 11-Tool-ToolParam-ToolCallResult

## 1. 这一章解决什么问题

第 8–10 章分别看了三个工具的创建，每个都用到了 `Tool` 和 `ToolParam`。第 6 章提到了 `ToolCallResult`。这三个类最容易混淆——它们的名字都带 "Tool"，但代表完全不同的东西。

这一章把三个类放在一起对比，建立清晰的边界：

```text
Tool           = 工具定义（说明书 + 执行函数）
ToolParam      = 参数定义（每个参数的 schema）
ToolCallResult = 一次调用记录（决策 + 参数值 + 执行结果）
```

## 2. 三个类的完整源码，源码里加注释

### 2.1 Tool（工具定义）

```java
/**
 * 位置：model/Tool.java (29 行)
 */
public class Tool {
    private String name;                                           // ① 工具唯一标识，如 "get_weather"
    private String description;                                    // ② 给 LLM 看的描述
    private List<ToolParam> parameters;                            // ③ 参数 schema 列表
    private boolean mcp;                                           // ④ 是否 MCP 外部工具
    private transient Function<Map<String, Object>, String> execute; // ⑤ 执行函数，transient 不序列化

    // 全参构造
    public Tool(String name, String description,
                List<ToolParam> parameters,
                Function<Map<String, Object>, String> execute) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.execute = execute;
    }
    // getters / setters ...
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 工具唯一标识，如 "get_weather"**

工具唯一标识，如 "get_weather"。它是工具注册、工具决策、工具查找三处共同使用的 key。

**② 给 LLM 看的描述**

给 LLM 看的描述。它帮助 Planner 理解工具用途，不参与 Java 里的关键词规则判断。

**③ 参数 schema 列表**

参数 schema 列表。这里声明的是工具对外暴露的参数说明，Planner、前端或调用方靠它知道应该传什么字段，但它不是本次调用的真实参数值。

**④ 是否 MCP 外部工具**

是否 MCP 外部工具。这个标记用于区分本地 lambda 工具和外部 MCP/HTTP 工具，方便展示或扩展执行策略。

**⑤ 执行函数，transient 不序列化**

执行函数，transient 不序列化。`execute` 是运行时函数对象，不能作为普通 JSON 字段保存；工具恢复时需要重新注册。


**`transient` 的含义**：`execute` 是 Lambda 表达式，不能序列化。`transient` 确保 `Tool` 对象被序列化（如存入缓存）时，`execute` 不会被写入。反序列化后 `execute` 为 `null`——这需要调用方重新设置。

### 2.2 ToolParam（参数定义）

```java
/**
 * 位置：model/ToolParam.java (23 行)
 */
public class ToolParam {
    private String name;        // ① 参数名，如 "city"
    private String type;        // ② 参数类型，如 "string"
    private String description; // ③ 参数描述，给 LLM 看的
    private boolean required;   // ④ 是否必填

    public ToolParam(String name, String type,
                     String description, boolean required) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
    }
    // getters / setters ...
}
```

**`type` 字段目前只填 `"string"`**。虽然没有实际类型校验，但它是给 LLM Planner 看的——LLM 看到 `"string"` 就知道该填文本。

### 2.3 ToolCallResult（一次调用记录）

```java
/**
 * 位置：model/ToolCallResult.java (21 行)
 */
public class ToolCallResult {
    private String toolName;              // ① 调了哪个工具
    private Map<String, Object> params;   // ② 本次调用传了什么参数值
    private String toolResult;            // ③ 工具执行后返回了什么

    // 两参构造：toolResult 初始为 null，执行后才赋值
    public ToolCallResult(String toolName, Map<String, Object> params) {
        this.toolName = toolName;
        this.params = params;
    }
    // getters / setters ...
}
```

## 3. 每个字段的含义和取值时机

### Tool 的字段

| 字段 | 谁设置 | 什么时候设置 | 例子 |
|---|---|---|---|
| `name` | `createGetWeatherTool()` | 启动时 | `"get_weather"` |
| `description` | `createGetWeatherTool()` | 启动时 | `"获取城市天气信息"` |
| `parameters` | `createGetWeatherTool()` | 启动时 | `[ToolParam("city","string","城市名称",true)]` |
| `mcp` | 默认 false，MCP 工具设 true | 启动时 | `false` |
| `execute` | `createGetWeatherTool()` 的 lambda | 启动时 | `params -> { ... }` |

**Tool 的字段在启动时全部确定，运行时不改变。** 一个 Tool 对象被整个系统共享，被多轮请求并发读取。所以 Tool 是无状态的——它描述"这个工具是什么"，不保存"上次调用结果"。

### ToolParam 的字段

| 字段 | 例子值 | 在什么场景被读取 |
|---|---|---|
| `name` | `"city"` | `PreferenceFiller.fill` 用它匹配 `PREF_TO_PARAM` 的候选参数名；`execute.apply` 用它从 params 取值 |
| `type` | `"string"` | `Planner.planGraph` 构建 LLM prompt 时告诉 LLM 参数类型 |
| `description` | `"城市名称"` | `Planner.planGraph` 构建 LLM prompt 时告诉 LLM 参数含义 |
| `required` | `true` | `Planner.planGraph` 告诉 LLM 这个参数必须提供 |

**ToolParam 也没有运行时状态。** 它是纯描述，不是"这个参数现在是什么值"。

### ToolCallResult 的字段

| 字段 | 什么时候赋值 | 谁赋值 | 例子 |
|---|---|---|---|
| `toolName` | `ToolService.decide` 返回时 | `ToolService.decide` | `"get_weather"` |
| `params` | `ToolService.decide` 返回时 | `ToolService.decide`，后可能被 `PreferenceFiller.fill` 修改 | `{city: "上海"}` |
| `toolResult` | 工具执行后 | `ToolModeHandler.run` | `"上海：小雨 20°C"` |

```text
ToolCallResult 的生命周期：

① decide 创建：  ToolCallResult("get_weather", {})         ← toolResult = null
② fill 补参：     params 变成 {city: "上海"}              ← toolResult = null
③ execute.apply： toolResult 变成 "上海：小雨 20°C"       ← 写入
④ resp.setToolCall(tc)：整个 tc 写入 ChatResponse
```

## 4. 三个类的关系

```text
Tool                          ToolParam
┌─────────────────┐           ┌─────────────┐
│ name: "get_weather"│◄───────│ name: "city" │
│ description: "..." │ 包含   │ type: "string"│
│ parameters ────────┘        │ required:true│
│ execute: lambda    │        └─────────────┘
└─────────────────┘
        │
        │ ts.get(toolName)          ToolCallResult
        │                      ┌────────────────────┐
        ├──────────────────────│ toolName:"get_weather"│
        │                      │ params: {city:"上海"} │
        │ tool.getExecute()    │ toolResult: "小雨20°C" │
        │   .apply(params)     └────────────────────┘
        │
        ▼
    "上海：小雨 20°C"
```

关键关系：

```text
Tool.parameters      描述"这个工具需要什么参数"        → 类型是 List<ToolParam>
ToolCallResult.params 记录"这次调用实际传了什么参数值"  → 类型是 Map<String, Object>

它们之间没有直接引用关系。
Tool 不知道 ToolCallResult 的存在。
ToolCallResult 通过 toolName 间接引用 Tool。
```

## 5. 这一步在完整链路中的位置

三个类在不同时刻被创建和使用：

```text
启动时：
  new Tool("get_weather", ..., [new ToolParam("city", ...)], lambda)
  创建时机：只在启动时创建一次
  存活范围：整个应用生命周期

请求时：
  new ToolCallResult("get_weather", {city: "上海"})
  创建时机：每次 tool 模式请求
  存活范围：单次请求，最终写入 ChatResponse
```

## 6. 用"上海天气怎么样？"跑一遍，看三个类的实例

```text
启动时已存在：
  Tool {
    name = "get_weather"
    parameters = [ToolParam { name="city", type="string", required=true }]
    execute = (params) -> { ... WEATHER_DB.getOrDefault ... }
  }

请求来时：
 ① ToolService.decide("上海天气怎么样？", tools)
    提取 city="上海"
    → new ToolCallResult("get_weather", {city: "上海"})
    tc.toolName = "get_weather"       ← 引用 Tool.name
    tc.params = {city: "上海"}         ← 真实参数值（不是 ToolParam）
    tc.toolResult = null               ← 还没执行

 ② ts.get("get_weather")
    → 拿到启动时创建的 Tool 对象

 ③ PreferenceFiller.fill(tc, pref)
    遍历 PREF_TO_PARAM："城市" → ["city","location","location_name"]
    tc.params 已有 city="上海" → 不覆盖
    tc.params 不变

 ④ tool.getExecute().apply(tc.params)
    params.get("city") → "上海"
    WEATHER_DB.get("上海") → "小雨 20°C"
    返回 "上海：小雨 20°C"
    tc.setToolResult("上海：小雨 20°C")  ← toolResult 现在有值了

 ⑤ llm.chat(sp, "用户问：上海天气怎么样？\n工具 get_weather 返回结果：上海：小雨 20°C")
    → "上海目前小雨，约 20°C，出门建议带伞。"
    resp.setAnswer(...)

 ⑥ resp.setToolCall(tc)
    → 前端可以看到完整的 ToolCallResult
```

## 7. 常见误解

**误解一："Tool 是工具调用，ToolCallResult 是工具定义"**

正好反了。`Tool` 是定义（不变的说明书 + 执行器），`ToolCallResult` 是调用（一次具体的调用记录）。

**误解二："ToolParam.name 和 params 的 key 必须是同一个值"**

是的，而且这是约定而不是强制约束。`ToolParam("city", ...)` 意味着参数的 schema 名字是 `"city"`。`params.put("city", "上海")` 用同一个 key。但 Java 代码中两者是独立的值——如果 `ToolParam.name` 是 `"city"` 而 `params.put("town", "上海")`，`execute.apply` 会用 `params.get("city")` 取到 `null`。

**误解三："ToolCallResult 在创建时 toolResult 就有值"**

没有。`ToolCallResult` 的两参构造函数只设置 `toolName` 和 `params`，`toolResult` 保持 `null`。它是在 `ToolModeHandler.run` 里通过 `tc.setToolResult(result)` 写入的。

**误解四："Tool.execute 直接返回给用户的最终答案"**

不是。`tool.getExecute().apply(params)` 返回的是原始数据（如 `"上海：小雨 20°C"`），最终回答是 LLM 基于这个数据生成的（如 `"上海目前小雨，约 20°C，出门建议带伞。"`）。详见第 16 章。

**误解五："ToolParam.required=true 的情况下，execute.apply 会校验参数"**

不会。`execute.apply` 的实现层没有任何参数校验。`required` 字段是给 LLM Planner 看的提示，不参与代码执行层面的 enforce。代码中的兜底逻辑（如 `city != null ? city : "北京"`）独立于 `required` 字段存在。
