# 08-ToolService-createGetTimeTool

## 1. 这个方法解决什么问题

一个工具对象怎么写？`createGetTimeTool` 是最简单的例子——它的参数全可选，逻辑只有 5 行，适合作为"创建工具"的入门。

它回答的问题是：**把一段可执行逻辑（获取当前时间）包装成一个 `Tool` 对象，需要写什么**。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ToolService.java:38-48
 */
private Tool createGetTimeTool() {
    return new Tool(                                               // ① 新建 Tool 对象
        "get_time",                                                // ② name：工具唯一标识
        "获取当前时间",                                             // ③ description：给 LLM 看的说明
        List.of(new ToolParam("timezone", "string",                // ④ parameters：参数 schema
                 "时区（如 Asia/Tokyo）", false)),                  //    timezone 可选（false）
        params -> {                                                // ⑤ execute：真正执行的 lambda
            String tz = params.get("timezone") != null             // ⑥ 从 params 取 timezone
                ? params.get("timezone").toString() : "";
            ZoneId zone = ZoneId.systemDefault();                  // ⑦ 默认系统时区
            if (!tz.isEmpty()) {
                try { zone = ZoneId.of(tz); } catch (Exception ignored) {}  // ⑧ 解析时区
            }
            return ZonedDateTime.now(zone)                         // ⑨ 获取当前时间并格式化
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        });
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 新建 Tool 对象**

新建 Tool 对象。这一步先准备容器或对象，后面的循环、写入或返回都依赖它。

**② name：工具唯一标识**

name：工具唯一标识。`get_time` 是工具库 Map 的 key，也是 `ToolCallResult.toolName` 要匹配的字符串。

**③ description：给 LLM 看的说明**

description：给 LLM 看的说明。Planner 构造 prompt 时会把它列出来，模型据此判断这个工具适不适合当前任务。

**④ parameters：参数 schema**

parameters：参数 schema。这里声明的是工具对外暴露的参数说明，Planner、前端或调用方靠它知道应该传什么字段，但它不是本次调用的真实参数值。

**⑤ execute：真正执行的 lambda**

execute：真正执行的 lambda。工具被调用时，`getExecute().apply(params)` 最终跑的就是这里定义的函数体。

**⑥ 从 params 取 timezone**

从 params 取 timezone。这里从本次调用参数 Map 中读取值；如果前面的 decide/fill 没有写入对应 key，这里就会走默认值或空值逻辑。

**⑦ 默认系统时区**

默认系统时区。这是兜底路径，保证参数缺失时工具仍能返回结果，但也可能掩盖上游参数没有补好的问题。

**⑧ 解析时区**

解析时区。这里把字符串参数转换成运行时对象；解析失败通常会被兜底处理，避免一次错误输入打断整条链路。

**⑨ 获取当前时间并格式化**

获取当前时间并格式化。这一步做数据形态转换，让下游方法拿到它期望的结构。


## 3. 参数逐个解释

无参数。这是 `ToolService` 的私有方法。

**但它的产出——Tool 构造函数的参数需要逐项解释**：

| Tool 构造函数参数 | 值 | 含义 |
|---|---|---|
| `name` | `"get_time"` | 工具名，`ts.get("get_time")` 用这个 key 查找 |
| `description` | `"获取当前时间"` | 给 LLM Planner 看的描述，LLM 据此决定是否调用 |
| `parameters` | `List.of(new ToolParam(...))` | 参数 schema，这里只有一个可选参数 |
| `execute` | `params -> { ... }` | `Function<Map<String,Object>, String>`，执行逻辑 |

**`ToolParam` 的四个字段**：

| 字段 | 值 | 含义 |
|---|---|---|
| `name` | `"timezone"` | 参数名，调用时 `params.get("timezone")` 取它 |
| `type` | `"string"` | 参数类型，目前所有参数都是 string |
| `description` | `"时区（如 Asia/Tokyo）"` | 给 LLM 看的说明 |
| `required` | `false` | 这个参数可以不传 |

**`required=false` 是理解这个方法的关键**。`get_time` 的参数全部可选——不传 timezone 就用系统默认时区，传了就按指定时区。这是最简单的工具模式：**零依赖参数的纯计算**。

## 4. 返回值/副作用解释

**返回值**：`Tool` 对象

**副作用**：无。创建 Tool 对象不执行任何逻辑。

**execute lambda 的逻辑**：

```text
params.get("timezone") == null → tz = "" → zone = 系统默认 → 返回本地时间
params.get("timezone") == "Asia/Tokyo" → zone = 东京时区 → 返回东京时间
params.get("timezone") == "invalid/zone" → ZoneId.of 抛异常 → catch 忽略 → zone = 系统默认
```

注意第九行：**时区解析失败被静默忽略，不会抛异常**。这是一种容错设计——用户传了错误的时区名，得到的仍是系统默认时间，工具不会因此失败。

## 5. 这一步在完整链路中的位置

```text
系统启动：
  getDefaultTools()
    → createGetTimeTool()  ← 你在这里
    → tools.put("get_time", ...)

请求时（如果 query 含"几点"/"时间"）：
  ToolService.decide → ToolCallResult("get_time", params)
  ToolModeHandler.run
    → ts.get("get_time") → 拿到 createGetTimeTool 创建的 Tool 对象
    → tool.getExecute().apply(params) → 执行上面的 lambda
```

`createGetTimeTool` 只在启动时调用一次。但它的产物（Tool 对象）会在每次查询时间时被使用。

## 6. 用"现在几点了？"跑一遍

```text
query = "现在几点了？"
↓
decideMode → needTool("现在几点了？")
→ "几点" 命中 → 返回 true
→ mode = "tool"
↓
ToolService.decide("现在几点了？", tools)
→ "几点" 命中 → params 无特殊时区
→ ToolCallResult("get_time", params={})
↓
ToolModeHandler.run:
  PreferenceFiller.fill(tc, pref)
    → tc.params 中没有 timezone
    → pref 中如果有"时区" → params.put("timezone", 偏好值)
  tool.getExecute().apply(tc.getParams())
    → params.get("timezone") → 可能是 "Asia/Tokyo"（偏好）
    → 或 null → 系统默认
    → 返回 "2026-06-22 14:30:00"
```

## 7. 常见误解

**误解一："required=false 意味着参数可以随意填"**

`required=false` 的含义是"不填也能执行"，不是"填什么都行"。填了错误的时区名会被 `ZoneId.of` 静默忽略——这是一个容错设计，但也是一个隐藏行为：用户不会被告知时区名错了。

**误解二："execute lambda 的参数 params 和 ToolParam 是一回事"**

完全不同。`ToolParam`（`new ToolParam("timezone", "string", ...)`）是参数**定义**——描述这个工具需要什么。`params`（`Map<String, Object>`）是参数**值**——这次调用实际传什么。详见第 11 章。

**误解三："get_time 不需要网络请求，所以它是纯函数"**

不是纯函数——它依赖系统时钟 `ZonedDateTime.now()`。相同输入在不同时刻会产出不同输出。但对工具系统来说，这不影响架构——execute 接口 `Function<Map<String,Object>, String>` 不要求纯函数。

**误解四："createGetTimeTool 的 execute 会抛出异常"**

不会。时区解析失败被 `catch (Exception ignored)` 吞掉了。整个 lambda 不会向调用方抛出任何异常——这是有意设计的容错策略。
