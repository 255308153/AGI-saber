# 14-PreferenceFiller-fill

## 1. 这个方法解决什么问题

用户偏好里存了"默认城市=上海"。用户问"今天天气怎么样？"——没提城市。`ToolService.decide` 从 query 里提取不到城市，`params` 是空的。

这时谁来补 `city`？`PreferenceFiller.fill`。

它的职责是：**在工具参数缺失时，从用户偏好中自动填入，但不覆盖用户显式说的内容**。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：PreferenceFiller.java (40 行完整文件)
 */
public final class PreferenceFiller {

    // ████████ 层 1：静态映射表 ████████
    // 把偏好 key（中文名）映射到工具参数名的候选列表。
    // "城市" 偏好可以同时补 city / location / location_name，
    // 因为不同工具可能用不同的参数名表示同一个概念。
    private static final Map<String, List<String>> PREF_TO_PARAM = Map.of(  // ① 不可变 Map
            "城市", List.of("city", "location", "location_name"),            // ② 城市偏好可以补 3 个参数
            "时区", List.of("timezone", "tz", "time_zone"),                  // ③ 时区同理
            "姓名", List.of("name", "username", "user_name"),                // ④ 姓名
            "语言", List.of("language", "lang"),                             // ⑤ 语言
            "国家", List.of("country", "nation")                            // ⑥ 国家
    );

    private PreferenceFiller() {}  // 工具类，禁止实例化

    /**
     * 参数：
     *   tc   - ToolCallResult，方法会直接修改 tc.params
     *   pref - PreferenceMemory，从中读取用户偏好
     *
     * 返回：void（副作用方法，修改 tc.params）
     */
    public static void fill(ToolCallResult tc, PreferenceMemory pref) {

        // ████████ 层 2：前置守卫 ████████
        if (tc == null || pref.getData().isEmpty()) return;         // ⑦ tc为null或偏好为空 → 直接返回

        // ████████ 层 3：外层循环 — 遍历偏好表 ████████
        for (Map.Entry<String, List<String>> e : PREF_TO_PARAM.entrySet()) { // ⑧ 遍历5个偏好项

            // ████████ 层 4：取偏好值 ████████
            String prefVal = pref.getData().get(e.getKey());       // ⑨ 从偏好表取"城市"的值
            if (prefVal == null || prefVal.isEmpty()) continue;    // ⑩ 偏好值为空 → 跳过这个偏好项

            // ████████ 层 5：内层循环 — 遍历候选参数名 ████████
            for (String paramName : e.getValue()) {                 // ⑪ 遍历 [city, location, location_name]

                // ████████ 层 6：检查工具是否需要这个参数 ████████
                Object v = tc.getParams().get(paramName);           // ⑫ 从 ToolCallResult.params 取值

                // ████████ 层 7：核心判断 — 只在缺失时填入 ████████
                if (v == null || v.toString().isEmpty()) {          // ⑬ 参数不存在或为空字符串
                    tc.getParams().put(paramName, prefVal);         // ⑭ 写入偏好值
                }
                // 如果参数已有非空值 → 什么都不做，保留原值
            }
        }
    }
}
```
### 2.1 逐行解释

下面按编号说明 `fill` 怎么决定“补不补参数”。核心原则只有一句：**只补缺失参数，不覆盖用户显式参数**。

**① 不可变 Map**

`PREF_TO_PARAM` 是一张静态映射表，用 `Map.of` 创建后不能修改。它描述的是“偏好记忆里的中文 key”如何对应“工具参数名”。后面 `fill` 不会猜参数名，只按这张表补。

**② 城市偏好可以补 3 个参数**

偏好记忆里存的是 `"城市"`，但工具参数可能叫 `city`、`location` 或 `location_name`。所以同一个偏好值会尝试补多个候选参数名。天气工具用的是 `city`，别的工具可能用 `location`。

**③ 时区同理**

`"时区"` 可以补 `timezone`、`tz`、`time_zone`。比如时间工具的参数名是 `timezone`，如果用户偏好里存了 `"时区" -> "Asia/Shanghai"`，并且本次 `params` 没有 `timezone`，就能补进去。

**④ 姓名**

`"姓名"` 对应常见参数名 `name`、`username`、`user_name`。当前默认工具用不到，但 MCP 工具或未来自定义工具可能会用。

**⑤ 语言**

`"语言"` 对应 `language`、`lang`。比如翻译类工具如果要求 `language`，用户偏好里保存的常用语言就可以自动补。

**⑥ 国家**

`"国家"` 对应 `country`、`nation`。这和城市、时区一样，都是把用户偏好转换成工具能识别的参数名。

**⑦ tc为null或偏好为空 → 直接返回**

这是前置守卫。`tc == null` 说明前面没有决策出工具调用；`pref.getData().isEmpty()` 说明没有任何偏好可用。两种情况都没法补参，所以直接返回，不修改任何东西。

**⑧ 遍历5个偏好项**

外层循环遍历映射表里的 5 类偏好：城市、时区、姓名、语言、国家。注意它遍历的是“可补的偏好类型”，不是遍历工具参数。这样做的前提是参数名必须在 `PREF_TO_PARAM` 里登记过。

**⑨ 从偏好表取"城市"的值**

用中文 key 从 `PreferenceMemory` 里取值。例如当前循环项是 `"城市"`，就执行 `pref.getData().get("城市")`，可能得到 `"上海"`。如果用户从未告诉系统城市，这里得到 `null`。

**⑩ 偏好值为空 → 跳过这个偏好项**

如果偏好值不存在或是空字符串，就 `continue` 跳过当前偏好类型。比如没有 `"时区"` 偏好，就不会尝试补 `timezone/tz/time_zone`。

**⑪ 遍历 [city, location, location_name]**

内层循环遍历这个偏好对应的候选参数名。以城市为例，会依次检查 `city`、`location`、`location_name`。哪个参数名在 `tc.params` 里缺失，就把偏好值写进去。

**⑫ 从 ToolCallResult.params 取值**

读取当前工具调用里的已有参数。比如 `tc.params={city:"上海"}`，取 `city` 得到 `"上海"`；如果 `tc.params={}`，取 `city` 得到 `null`。

**⑬ 参数不存在或为空字符串**

这是补参决策的核心。只有两种情况允许补：参数不存在，即 `v == null`；参数存在但值是空字符串，即 `v.toString().isEmpty()`。如果用户已经明确写了 `city:"上海"`，这里条件为 false，不会覆盖。

**⑭ 写入偏好值**

真正写入发生在这里：`tc.getParams().put(paramName, prefVal)`。写入后，后面的 `tool.getExecute().apply(tc.getParams())` 会拿到补全后的参数。例如 `{}` 变成 `{city:"上海"}`，天气工具才能按上海查询。


## 3. 参数逐个解释

| 参数 | 类型 | 来源 | 可修改 |
|---|---|---|---|
| `tc` | `ToolCallResult` | `ToolModeHandler.run` 第 ① 步 | **是——tc.params 会被直接 put** |
| `pref` | `PreferenceMemory` | `ToolModeHandler` 构造函数注入 | 否——只读取 |

**`pref.getData()` 返回什么？**

```text
ConcurrentHashMap<String, String>：
  "城市" → "上海"
  "时区" → "Asia/Shanghai"
  "语言" → "中文"
  ...
```

这是用户在对话中逐渐积累的偏好。`pref.extractAndSave(query)` 在 `processInternal` 中持续更新它。

## 4. 返回值/副作用解释

**返回值**：`void`

**副作用**：直接修改 `tc.params`（`Map<String, Object>`）。这是 Java 里典型的"传入可变对象，在方法内修改其字段"模式。

**`fill` 的七层结构**：

```text
层 1：PREF_TO_PARAM 静态映射表
层 2：前置守卫（tc null? pref 空?）
层 3：外层循环 → 遍历 5 个偏好维度
层 4：取偏好值 → 从 PreferenceMemory 读
层 5：内层循环 → 遍历候选参数名
层 6：检查工具参数 → 从 tc.params 读
层 7：核心判断 → null 或空才写入
```

**为什么是七层？**

因为映射关系是二层嵌套：`偏好 key → [候选参数名列表]`。外层遍历偏好维度，内层遍历候选参数名。两层嵌套 + 前置守卫 + 空值检查 + 核心判断 = 七层。

## 5. 这一步在完整链路中的位置

```text
ToolModeHandler.run
  ├── ① ToolService.decide(query, ts) → ToolCallResult(toolName, params)
  │      params = {city: "上海"} 或 {}   ← 取决于 query 里有没说城市
  ├── ② ts.get(tc.getToolName())
  ├── ③ PreferenceFiller.fill(tc, pref)  ← 你在这里
  │      在 ① 和 ④ 之间，params 从"初值"变成"补全值"
  ├── ④ tool.getExecute().apply(tc.getParams())
  └── ⑤ llm.chat
```

**为什么必须在 decide 之后、execute 之前？**

- `decide` 之后：因为 `fill` 需要 `tc.params` 已经创建（不管是否为空）
- `execute` 之前：因为工具执行时需要完整的参数

## 6. 用两个场景跑一遍，展示"补"和"不补"的边界

### 场景 A：用户说了城市 → 不补

```text
query = "上海天气怎么样？"

① decide 产出：
   tc.toolName = "get_weather"
   tc.params = {city: "上海"}          ← 用户显式说了

③ fill 执行：
   pref.getData() = {"城市": "杭州"}    ← 偏好城市是杭州
   遍历 PREF_TO_PARAM："城市" → [city, location, location_name]
   取偏好值：prefVal = "杭州"
   遍历候选参数名：
     paramName="city"
     v = tc.params.get("city") → "上海"  ← 非空！
     v.toString().isEmpty() → false     ← 不满足"缺失"条件
     → 不写入！保留 "上海"

④ execute.apply({city: "上海"})
   → 查的是上海天气，不是杭州 ← 用户显式意图被尊重
```

### 场景 B：用户没说城市 → 补

```text
query = "今天天气怎么样？"

① decide 产出：
   tc.toolName = "get_weather"
   tc.params = {}                    ← 没有 city

③ fill 执行：
   pref.getData() = {"城市": "上海"}
   遍历 PREF_TO_PARAM："城市" → [city, location, location_name]
   取偏好值：prefVal = "上海"
   遍历候选参数名：
     paramName="city"
     v = tc.params.get("city") → null  ← 不存在！
     v == null → true                 ← 满足"缺失"条件
     → tc.params.put("city", "上海")  ← 写入偏好值

     paramName="location"
     v = tc.params.get("location") → null → 写入"上海"

     paramName="location_name"
     v = tc.params.get("location_name") → null → 写入"上海"

   tc.params = {city: "上海", location: "上海", location_name: "上海"}

④ execute.apply({city: "上海", location: "上海", location_name: "上海"})
   → params.get("city") → "上海"
   → 查上海天气 ✓  ← 用户没说城市，但偏好补上了
```

### 场景 C：decide 给了默认值 → fill 被阻碍

```text
如果 ToolService.decide 天气分支这样写：
  String city = "北京";  // 默认值
  ...遍历城市...
  params.put("city", city);  // 总是写入 city
  return new ToolCallResult("get_weather", params);

那么"今天天气怎么样？"进来：
  decide 产出 tc.params = {city: "北京"}  ← 硬编码默认值

③ fill 执行：
   v = tc.params.get("city") → "北京"   ← 非空
   → 不写入！
   → 偏好城市"上海"被忽略
   → 最终查的是北京天气，不是上海  ← BUG
```

**这就是为什么当前的 `ToolService.decide` 天气分支不填默认 city**。代码里注释写着"留给 PreferenceFiller.fill() 从偏好记忆补充"。如果把默认值放在 decide 里，fill 就永远没机会生效。

## 7. 常见误解

**误解一："fill 会覆盖用户显式输入"**

不会。这是 `fill` 最重要的设计约束——第 ⑬ 行的 `if (v == null || v.toString().isEmpty())` 保证只在参数缺失时填入。用户说了"上海"，偏好里是"杭州"，最终用"上海"。

**误解二："fill 只补 `tc.toolName` 对应工具的缺失参数"**

不是。`fill` 不检查 `tc.toolName`。它对所有 5 个偏好维度执行——即使当前工具是 `get_time`（不需要 `city`），`fill` 仍会遍历城市维度的候选参数名。只是因为 `get_time` 的 lambda 里不读 `city`，多写的参数无害。

**误解三："PREF_TO_PARAM 的候选列表是随便写的"**

不是。`["city", "location", "location_name"]` 映射了不同工具可能使用的参数名。如果以后加了一个工具用 `"town"` 做参数名，需要在列表里加 `"town"`，否则即使有城市偏好也不会补到那个工具。

**误解四："fill 只对 get_weather 有效"**

"城市"偏好最常影响 `get_weather`，但 `search_web` 的 `query` 也可能受益于"语言"偏好（如用偏好语言改写 query）。不过当前实现中，`search_web` 的参数是 `query` 而不是 `language`，所以"语言"偏好对它没影响。映射表的设计是为了**可扩展**——换了参数名也能生效。

**误解五："如果 pref.getData() 为空，fill 会抛 NPE"**

不会。第 ⑦ 行 `pref.getData().isEmpty()` 有前置守卫——`pref.getData()` 返回的是 `ConcurrentHashMap`，`isEmpty()` 安全。

**误解六："fill 修改 params 后，tool.execute 一定会用到新值"**

是的——因为 `execute.apply(tc.getParams())` 传入的是 `tc.params` 的引用，fill 对它的修改在执行前已经生效。但如果某个工具在内部做了 `new HashMap<>(params)` 复制，那就不会受影响——不过当前所有内置工具的 lambda 都是直接从 params 读取。
