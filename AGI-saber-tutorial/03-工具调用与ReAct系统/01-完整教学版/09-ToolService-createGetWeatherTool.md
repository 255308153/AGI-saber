# 09-ToolService-createGetWeatherTool

## 1. 这个方法解决什么问题

和第 8 章的 `get_time` 比，`get_weather` 多了一个关键差异：**它有一个必填参数 `city`**。而且它的工具结果来自内部 mock 数据库（`WEATHER_DB`），不是纯计算。

这引出了三个问题：
1. 必填参数怎么声明？
2. 如果 `city` 没传进来，兜底逻辑怎么写？
3. 未知城市怎么处理？

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：ToolService.java:51-58
 */
private Tool createGetWeatherTool() {
    return new Tool(
        "get_weather",                                             // ① 工具名
        "获取城市天气信息",                                         // ② 描述：LLM 据此理解用途
        List.of(new ToolParam("city", "string", "城市名称", true)),// ③ 参数 schema：city 必填
        params -> {                                                // ④ execute lambda
            String city = params.get("city") != null               // ⑤ 取 city 参数
                ? params.get("city").toString() : "北京";           //    未传则默认"北京"
            String weather = WEATHER_DB.getOrDefault(               // ⑥ 查 mock 数据库
                city, "晴天 20°C（模拟）");
            return city + "：" + weather;                          // ⑦ 拼接结果
        });
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 工具名**

工具名。这是工具在 `Map<String, Tool>` 里的唯一 key，必须和决策阶段返回的 toolName 保持一致。

**② 描述：LLM 据此理解用途**

描述：LLM 据此理解用途。它不会影响 Java 规则分支，但会影响 LLM Planner 对工具能力的理解。

**③ 参数 schema：city 必填**

参数 schema：city 必填。这里声明的是工具对外暴露的参数说明，Planner、前端或调用方靠它知道应该传什么字段，但它不是本次调用的真实参数值。

**④ execute lambda**

execute lambda。从这里开始定义工具真正执行时要跑的函数；前面的 name、description、parameters 都只是元数据。

**⑤ 取 city 参数**

取 city 参数。这里从本次调用参数 Map 中读取值；如果前面的 decide/fill 没有写入对应 key，这里就会走默认值或空值逻辑。

**⑥ 查 mock 数据库**

查 mock 数据库。用 city 作为 key 去 `WEATHER_DB` 取天气；如果城市不存在，就用模拟默认天气兜底。

**⑦ 拼接结果**

拼接结果。工具返回统一的字符串，例如 `上海：小雨 20°C`，后面 LLM 会基于这个原始结果生成自然语言。


**静态数据库**（同一个文件第 20–28 行）：

```java
private static final Map<String, String> WEATHER_DB = Map.of(
    "北京", "晴天 22°C",
    "东京", "多云 18°C 湿度65%",
    "上海", "小雨 20°C",
    "纽约", "晴天 15°C",
    "伦敦", "阴天 12°C",
    "广州", "晴天 28°C",
    "深圳", "晴天 26°C"
);
```

## 3. 参数逐个解释

无参数（私有方法）。

**ToolParam 对比**：

| | get_time | get_weather |
|---|---|---|
| 参数名 | `timezone` | `city` |
| required | `false` | **`true`** |
| 默认值 | 系统时区 | `"北京"` |

**`required=true` 的真正含义**：这个参数在"概念层面"必填。但实际代码里 `city` 为 null 时不会报错——它会被兜底为 `"北京"`。所以 `required=true` 更多是给 **LLM Planner** 看的：LLM 在规划时会优先从 query 中提取 city；`ToolService.decide` 也会从 query 中提取 city。

## 4. 返回值/副作用解释

**返回值**：`Tool` 对象

**execute 的返回值格式**：

```text
"上海：小雨 20°C"
"flkajdsf：晴天 20°C（模拟）"    ← 未知城市
```

**三层兜底**：

| 层级 | 条件 | city 值 | 来源 |
|---|---|---|---|
| 1 | query 含城市名 | `"上海"` | `ToolService.decide` 提取 |
| 2 | query 不含城市，偏好有城市 | `"上海"` | `PreferenceFiller.fill` 补充 |
| 3 | query 无城市，偏好无城市 | `"北京"` | execute lambda 硬编码 |
| 4 | city 存在但不在 WEATHER_DB 中 | 不变 | `getOrDefault` 返回模拟值 |

**层级 3 是一个设计瑕疵**。`"北京"` 作为兜底值，会阻碍 `PreferenceFiller.fill` 的补参逻辑——第 14 章会详细讨论这个问题。

## 5. 这一步在完整链路中的位置

```text
系统启动：
  getDefaultTools()
    → createGetWeatherTool()  ← 你在这里
    → tools.put("get_weather", ...)

请求时：
  ToolService.decide("上海天气怎么样？", tools)
    → 提取 city="上海"
    → ToolCallResult("get_weather", {city: "上海"})

  PreferenceFiller.fill(tc, pref)
    → city 已存在（"上海"），不覆盖

  ts.get("get_weather").getExecute().apply({city: "上海"})
    → WEATHER_DB.get("上海") → "小雨 20°C"
    → 返回 "上海：小雨 20°C"

  llm.chat → 自然语言总结
```

## 6. 用两个场景跑一遍

**场景 A：query 含城市名**

```text
query = "上海天气怎么样？"

decide → 提取 city="上海"
       → ToolCallResult("get_weather", params={city: "上海"})

fill   → city 已有值，跳过

execute.apply({city: "上海"})
  → city = "上海"（不为 null，跳过默认"北京"）
  → WEATHER_DB.get("上海") → "小雨 20°C"
  → 返回 "上海：小雨 20°C"
```

**场景 B：query 不含城市名**

```text
query = "今天天气怎么样？"

decide → 没匹配到任何城市
       → ToolCallResult("get_weather", params={})  ← city 不在这里

fill   → pref 中有"城市"："上海"
       → params 中 city 为 null
       → params.put("city", "上海")  ← 偏好补参

execute.apply({city: "上海"})
  → city = "上海"（不为 null，跳过默认"北京"）
  → 返回 "上海：小雨 20°C"
```

## 7. 常见误解

**误解一："required=true 意味着不传 city 就会报错"**

不会。execute lambda 里有 `null → "北京"` 的兜底。`required=true` 是给 LLM 看的语义约束，不是代码层级的 enforce。

**误解二："WEATHER_DB 只包含 7 个城市，其他城市查不到就报错"**

不会。未知城市走 `getOrDefault` 兜底 `"晴天 20°C（模拟）"`——永远有返回值。

**误解三："createGetWeatherTool 的 city 默认值 '北京' 不影响 PreferenceFiller"**

实际上有影响。如果 `ToolService.decide` 不给 city 赋值（`params={}`），`fill` 可以补上偏好城市。但如果 `decide` 赋了 `"北京"` 作为默认值，`fill` 看到 city 已有值就不会补——这是第 14 章的核心议题。

**误解四："真实项目中只需要把 WEATHER_DB 换成 API 调用"**

结构上是的——execute lambda 内部可以任意替换，外部接口不变。但真实天气 API 还涉及：异步调用、超时处理、API key 管理、返回格式解析。这些复杂度被 `Function<Map<String,Object>, String>` 接口完全封装在 lambda 内部。
