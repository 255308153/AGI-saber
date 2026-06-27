# 10-ToolService-createSearchWebTool

## 1. 这一篇到底讲什么

这一篇要把 `search_web` 讲清楚，因为它很容易误解。

先记住一句话：

```text
ToolService.createSearchWebTool()
  只是创建一个默认的、模拟版的 search_web。

UnifiedAgentService.init()
  会用同名 key 再 put 一次 search_web，
  把默认模拟版覆盖成 Tavily + LLM fallback 版。
```

所以按你现在说的“配置了 Tavily”来看，运行时真正执行的是：

```text
用户问题
  → ToolService.decide(...) 决策出 toolName = "search_web"
  → ToolModeHandler 根据 toolName 从 tools Map 里取工具
  → 取到的是 UnifiedAgentService.init() 覆盖后的 Tavily 版 search_web
  → TavilyClient.search(...) 发 HTTP POST 真实搜索
  → 返回搜索结果给 LLM 总结
```

`ToolService.createSearchWebTool()` 这段源码仍然要学，因为它说明了一个工具是怎么被定义出来的：工具名、描述、参数列表、执行函数。但是它不是配置 Tavily 后最终运行的联网搜索实现。

## 2. 默认模拟版 search_web：ToolService.createSearchWebTool

### 2.1 方法源码，源码里加注释

```java
/**
 * 位置：ToolService.java
 *
 * 这个方法创建的是默认版 search_web。
 * 默认版不会联网，只根据 query 做字符串匹配，然后返回模拟结果。
 */
private Tool createSearchWebTool() {
    return new Tool(
            "search_web",                                      // ① 工具名，也是 tools Map 里的 key
            "搜索互联网获取最新信息",                            // ② 工具描述，说明这个工具的用途
            List.of(                                           // ③ 工具参数定义列表
                    new ToolParam(
                            "query",                           // ④ 参数名：调用工具时必须传 query
                            "string",                          // ⑤ 参数类型：字符串
                            "搜索关键词",                        // ⑥ 参数说明：告诉调用方 query 是搜索关键词
                            true                               // ⑦ required=true，表示 query 是必填参数
                    )
            ),
            params -> {                                        // ⑧ 工具执行函数：真正调用工具时执行这里
                String q = params.get("query") != null         // ⑨ 从 params 里取 query 参数
                        ? params.get("query").toString()        // ⑩ 如果 query 不为空，转成字符串
                        : "";                                  // ⑪ 如果没传 query，就用空字符串兜底

                // Mock search results
                if (q.contains("AI应用工程师")                  // ⑫ 如果 query 包含“AI应用工程师”
                        || q.contains("AI工程师")) {            // ⑬ 或者包含“AI工程师”
                    return "AI 应用工程师是将 AI 技术落地到业务的工程师，"
                            + "需具备 ML 基础、API 开发、Prompt 工程等能力。"; // ⑭ 返回固定模拟答案
                }

                if (q.contains("Go语言")                        // ⑮ 如果 query 包含“Go语言”
                        || q.contains("Go")) {                  // ⑯ 或者包含“Go”
                    return "Go 是 Google 开发的开源编程语言，"
                            + "适用于高并发服务端应用。Docker 即用 Go 开发。"; // ⑰ 返回固定模拟答案
                }

                return String.format(                           // ⑱ 其他问题统一返回通用模拟结果
                        "关于「%s」的搜索结果（模拟）",
                        q
                );
            });
}
```

### 2.2 逐行解释

**① `"search_web"`**

这是工具名，也是 `tools` 这个 `Map<String, Tool>` 里的 key。

后面执行工具时不是根据 Java 方法名找工具，而是根据这个字符串找：

```java
tools.get("search_web")
```

所以 `ToolService.decide(...)` 决策出来的 `toolName` 必须也是 `"search_web"`。

**② `"搜索互联网获取最新信息"`**

这是工具描述。它告诉系统这个工具的能力是什么。

注意，描述写着“搜索互联网”，不代表这段默认实现真的联网。默认实现只是 mock。真正联网是在 `UnifiedAgentService.init()` 里覆盖后的版本完成的。

**③ `List.of(...)`**

这里开始定义工具参数列表。

一个工具可以有多个参数，比如天气工具有 `city`，搜索工具这里只有一个 `query`。

**④ `"query"`**

参数名是 `query`。

调用搜索工具时，参数 Map 里必须长这样：

```java
Map.of("query", "查一下 Go 语言最新版本")
```

如果 key 写错，比如写成 `"keyword"`，执行函数里这句就取不到值：

```java
params.get("query")
```

**⑤ `"string"`**

参数类型是字符串。这个字段主要用于工具元数据说明，不会自动做强类型校验。

真正执行时，代码还是通过：

```java
params.get("query").toString()
```

把参数值转成字符串。

**⑥ `"搜索关键词"`**

这是给调用方看的参数说明。它说明 `query` 这个参数应该放搜索关键词或搜索问题。

**⑦ `true`**

表示 `query` 是必填参数。

但这里的 `true` 只是参数元数据。真正防止空 query 的逻辑，不在默认 mock 版里，而在 `UnifiedAgentService.init()` 覆盖后的 Tavily 版里：

```java
if (q.isEmpty()) throw new RuntimeException("搜索关键词不能为空");
```

**⑧ `params -> { ... }`**

这是工具的执行函数。

`Tool` 对象分成两部分：

```text
元数据：
  name
  description
  parameters

执行逻辑：
  params -> { ... }
```

只有真正调用工具时，才会进入这个 lambda。

**⑨ `params.get("query") != null`**

先判断调用方有没有传 `query`。

`params` 是工具调用参数，比如：

```java
{
  "query": "查一下 Go 语言"
}
```

如果 `params` 里没有 `query`，这里就是 `null`。

**⑩ `params.get("query").toString()`**

如果传了 `query`，就把它转成字符串。

这里不会改写 query，也不会提取关键词。用户问什么，就用什么。

例如用户输入：

```text
查一下 Go 语言最新版本
```

那么这里的 `q` 就是：

```text
查一下 Go 语言最新版本
```

**⑪ `: ""`**

如果没传 `query`，默认 mock 版会把 `q` 设置为空字符串。

这会导致最后返回：

```text
关于「」的搜索结果（模拟）
```

所以默认 mock 版对空 query 不严格。Tavily 版会更严格，空 query 会直接抛异常。

**⑫-⑭ AI 工程师模拟分支**

如果 query 里包含：

```text
AI应用工程师
AI工程师
```

就直接返回一段固定答案。

这不是搜索结果，而是写死在代码里的模拟结果。

**⑮-⑰ Go 语言模拟分支**

如果 query 里包含：

```text
Go语言
Go
```

就返回 Go 语言的固定模拟答案。

这里要注意大小写：代码检查的是大写 `Go`，没有先 `toLowerCase()`。

所以：

```text
查一下 Go 语言    → 命中
查一下 go 语言    → 不一定命中 Go 分支，可能走通用模拟结果
```

**⑱ 通用模拟结果**

如果前两个分支都没命中，就返回：

```text
关于「用户原始 query」的搜索结果（模拟）
```

这个结果只是占位，目的是让工具调用链路能跑通。

## 3. search_web 的参数为什么是整句 query

搜索工具和天气工具不一样。

天气工具要提取城市：

```text
用户：今天东京天气怎么样？
参数：city = "东京"
```

搜索工具不提取城市，也不提取关键词，而是把用户原始问题直接放进 `query`：

```text
用户：查一下 Go 语言最新版本
参数：query = "查一下 Go 语言最新版本"
```

原因是搜索引擎本来就可以处理自然语言问题。如果过早把用户问题拆碎，反而可能丢信息。

默认搜索工具的参数定义是：

| 字段 | 值 | 作用 |
|---|---|---|
| name | `"query"` | 执行函数从 `params.get("query")` 取值 |
| type | `"string"` | 说明 query 是字符串 |
| description | `"搜索关键词"` | 说明这个参数要放搜索内容 |
| required | `true` | 表示调用工具时必须传 |

## 4. ToolService.decide 是怎么决策出 search_web 的

`createSearchWebTool()` 只负责创建工具，不负责判断用户是不是要搜索。

判断用户要不要调用搜索工具的是 `ToolService.decide(...)`。

### 4.1 决策源码

```java
/**
 * 位置：ToolService.java
 *
 * decide 根据用户 query 决定要调用哪个工具。
 */
public ToolCallResult decide(String query, Map<String, Tool> tools) {
    String q = query.toLowerCase();                              // ① 把用户问题转成小写，用于规则匹配

    if ((q.contains("几点") || q.contains("时间"))                 // ② 如果问题包含“几点”或“时间”
            && tools.containsKey("get_time")) {                  // ③ 并且工具表里有 get_time
        Map<String, Object> params = new HashMap<>();             // ④ 创建参数 Map
        if (q.contains("东京")) params.put("timezone", "Asia/Tokyo"); // ⑤ 如果包含东京，补 timezone
        return new ToolCallResult("get_time", params);            // ⑥ 返回时间工具调用结果
    }

    if (q.contains("天气") && tools.containsKey("get_weather")) { // ⑦ 如果包含天气，并且有天气工具
        Map<String, Object> params = new HashMap<>();             // ⑧ 创建参数 Map
        for (String c : List.of("东京", "北京", "上海", "纽约", "伦敦", "广州", "深圳")) { // ⑨ 遍历支持的城市
            if (q.contains(c)) {                                  // ⑩ 如果用户问题里包含这个城市名
                params.put("city", c);                            // ⑪ 写入 city 参数
                break;                                            // ⑫ 找到一个城市就停止
            }
        }
        return new ToolCallResult("get_weather", params);         // ⑬ 返回天气工具调用结果
    }

    if ((q.contains("查")                                        // ⑭ 如果问题包含“查”
            || q.contains("搜索")                                 // ⑮ 或者包含“搜索”
            || q.contains("是什么"))                              // ⑯ 或者包含“是什么”
            && tools.containsKey("search_web")) {                 // ⑰ 并且工具表里有 search_web
        return new ToolCallResult(                                // ⑱ 决策为调用 search_web
                "search_web",                                    // ⑲ toolName = search_web
                Map.of("query", query)                            // ⑳ 参数 query = 用户原始问题，不用小写 q
        );
    }

    for (String name : tools.keySet()) {                          // ㉑ 如果前面规则都没命中，遍历工具表
        return new ToolCallResult(name, Map.of("query", query));  // ㉒ 返回第一个工具作为兜底
    }
    return null;                                                  // ㉓ 如果工具表为空，返回 null
}
```

### 4.2 search_web 决策逐行解释

重点看搜索分支：

```java
if ((q.contains("查")
        || q.contains("搜索")
        || q.contains("是什么"))
        && tools.containsKey("search_web")) {
    return new ToolCallResult(
            "search_web",
            Map.of("query", query)
    );
}
```

**第一步：判断用户意图**

只要用户问题里有下面任意一个词：

```text
查
搜索
是什么
```

系统就认为用户可能需要搜索。

例如：

```text
查一下 LangChain 是什么
搜索一下 Tavily 怎么用
MCP 是什么
```

都会命中搜索分支。

**第二步：确认工具表里真的有 search_web**

这句很关键：

```java
tools.containsKey("search_web")
```

它不是检查 `ToolService` 里有没有 `createSearchWebTool()` 方法，而是检查运行时 `tools` Map 里有没有 `"search_web"` 这个 key。

因为后面执行工具时也是从这个 Map 里取：

```java
tools.get("search_web")
```

**第三步：创建 ToolCallResult**

命中搜索规则后，返回：

```java
new ToolCallResult("search_web", Map.of("query", query))
```

这个对象表达的是：

```text
我要调用工具：search_web
调用参数是：query = 用户原始输入
```

例如用户输入：

```text
查一下 Go 语言最新版本
```

得到的 `ToolCallResult` 是：

```text
toolName = "search_web"
params = {
  "query": "查一下 Go 语言最新版本"
}
```

注意这里放的是原始 `query`，不是小写后的 `q`。

小写 `q` 只用于规则判断。真正传给搜索工具的还是用户原句。

## 5. 运行时 search_web 为什么会变成 Tavily 版

现在进入最关键的一层。

`ToolService.getDefaultTools()` 会先注册默认工具：

```java
public Map<String, Tool> getDefaultTools() {
    Map<String, Tool> tools = new ConcurrentHashMap<>();
    tools.put("get_time", createGetTimeTool());
    tools.put("get_weather", createGetWeatherTool());
    tools.put("search_web", createSearchWebTool()); // ① 这里先放入默认 mock 版 search_web
    return tools;
}
```

但是应用启动时，`UnifiedAgentService.init()` 又做了一次同名注册。

### 5.1 UnifiedAgentService.init 源码

```java
/**
 * 位置：UnifiedAgentService.java
 *
 * 应用启动后会执行 init()。
 * 这里先加载默认工具，然后覆盖 search_web。
 */
@PostConstruct
public void init() {
    stm.setMaxTurns(cfg.getMemory().getShortTermMaxTurns());      // ① 初始化短期记忆最大轮数
    ltm.setConsolidationConfig(cfg.getMemory().getConsolidation());// ② 初始化长期记忆配置

    tools.putAll(toolService.getDefaultTools());                  // ③ 把默认工具放进运行时 tools Map

    // 中间省略 RAG 等其他初始化代码

    tools.put("search_web", new Tool(                             // ④ 再次用同名 key 注册 search_web
            "search_web",                                        // ⑤ 工具名仍然是 search_web
            "搜索互联网获取最新信息",                              // ⑥ 工具描述
            List.of(new ToolParam("query", "string", "搜索关键词", true)), // ⑦ 参数仍然是 query
            params -> {                                          // ⑧ 新的执行函数：Tavily + LLM fallback
                String q = params.get("query") != null           // ⑨ 从 params 取 query
                        ? params.get("query").toString()          // ⑩ 转成字符串
                        : "";                                    // ⑪ 没传则为空

                if (q.isEmpty()) {                                // ⑫ 如果 query 是空字符串
                    throw new RuntimeException("搜索关键词不能为空"); // ⑬ 直接报错，不执行搜索
                }

                if (cfg.getSearch().getApiKey() != null           // ⑭ 如果 Tavily API key 不为 null
                        && !cfg.getSearch().getApiKey().isEmpty()) { // ⑮ 并且不是空字符串
                    try {
                        return TavilyClient.search(               // ⑯ 调用 TavilyClient 发起真实搜索
                                q,                                // ⑰ 搜索问题
                                cfg.getSearch().getApiKey(),      // ⑱ Tavily API key
                                cfg.getSearch().getApiUrl()       // ⑲ Tavily API URL
                        );
                    } catch (Exception ignored) {                 // ⑳ Tavily 调用失败时吞掉异常
                    }
                }

                return llm.chat(                                  // ㉑ 没配置 key 或 Tavily 失败，走 LLM fallback
                        "你是一个知识丰富的搜索引擎助手。请基于你的知识，"
                                + "对用户的搜索问题给出准确、详细的回答。"
                                + "直接给出答案，不要说「我不知道」或「我无法搜索」。",
                        List.of(Map.of("role", "user", "content", "搜索：" + q))
                );
            }));

    // 后面继续初始化其他模块
}
```

### 5.2 覆盖逻辑逐行解释

**③ `tools.putAll(toolService.getDefaultTools())`**

这一步会把默认工具全部放入 `UnifiedAgentService` 的运行时工具表：

```text
get_time
get_weather
search_web  ← 这里是 ToolService.createSearchWebTool() 创建的 mock 版
```

此时 `search_web` 还只是模拟版。

**④ `tools.put("search_web", new Tool(...))`**

这是同一个 Map 的第二次写入。

因为 key 还是：

```text
search_web
```

所以这次 `put` 不是新增一个第二个搜索工具，而是覆盖原来的搜索工具。

覆盖前：

```text
tools["search_web"] = ToolService.createSearchWebTool() 创建的 mock Tool
```

覆盖后：

```text
tools["search_web"] = UnifiedAgentService.init() 创建的 Tavily + LLM fallback Tool
```

这就是为什么“按配置 Tavily 来说”，最终执行的是真实搜索。

**⑧ `params -> { ... }`**

覆盖后的工具执行函数已经不是 mock 里的字符串匹配了。

它的新执行逻辑是：

```text
取 query
  → query 为空就报错
  → 有 Tavily API key 就调用 TavilyClient.search(...)
  → Tavily 成功就返回 Tavily 结果
  → 没 key 或 Tavily 失败就走 llm.chat(...) fallback
```

**⑫-⑬ 空 query 检查**

覆盖后的版本更严格。

如果 `query` 是空字符串，直接抛异常：

```java
throw new RuntimeException("搜索关键词不能为空");
```

这能避免拿空字符串去调用搜索 API。

**⑭-⑮ 判断 Tavily 是否配置**

这两行判断配置里有没有搜索 API key：

```java
cfg.getSearch().getApiKey() != null
&& !cfg.getSearch().getApiKey().isEmpty()
```

只要 key 存在且不为空，就尝试真实搜索。

**⑯ `TavilyClient.search(...)`**

这一步才是真正的联网搜索入口。

传进去三个值：

| 参数 | 来源 | 含义 |
|---|---|---|
| `q` | 工具调用参数 | 搜索问题 |
| `cfg.getSearch().getApiKey()` | 应用配置 | Tavily API key |
| `cfg.getSearch().getApiUrl()` | 应用配置 | Tavily API 地址 |

**⑳ `catch (Exception ignored) {}`**

如果 Tavily 请求失败，比如网络失败、key 错误、接口返回错误，这里会吞掉异常。

吞掉异常之后不会回到 `ToolService.createSearchWebTool()` 的 mock 版，而是继续往下走 LLM fallback。

这是一个非常重要的点：

```text
没有 API key / Tavily 失败
  → 走覆盖后工具里的 llm.chat fallback
  → 不会回到默认 mock search_web
```

**㉑ `llm.chat(...)`**

这是兜底路径。

它不是真实搜索，而是让 LLM 根据已有知识回答。

所以三种情况要分清：

| 情况 | 最终执行 |
|---|---|
| 只看 `ToolService.createSearchWebTool()` | mock 字符串匹配 |
| 正常应用启动，配置 Tavily key，Tavily 成功 | Tavily 真实联网搜索 |
| 正常应用启动，没配置 key 或 Tavily 失败 | 覆盖后工具里的 LLM fallback |

## 6. TavilyClient.search 是怎么真的联网的

现在看真正搜索的源码。

### 6.1 TavilyClient.search 源码

```java
/**
 * 位置：TavilyClient.java
 *
 * 这个方法负责向 Tavily Search API 发送 HTTP POST 请求。
 */
public static String search(String query, String apiKey, String apiUrl) throws Exception {
    if (apiUrl == null || apiUrl.isEmpty()) {                     // ① 如果没配置 apiUrl
        apiUrl = "https://api.tavily.com/search";                 // ② 使用 Tavily 默认搜索地址
    }

    Map<String, Object> body = new LinkedHashMap<>();             // ③ 创建请求 body
    body.put("api_key", apiKey);                                  // ④ 放入 Tavily API key
    body.put("query", query);                                     // ⑤ 放入搜索 query
    body.put("search_depth", "basic");                            // ⑥ 搜索深度：basic
    body.put("max_results", 5);                                   // ⑦ 最多返回 5 条结果

    String json = mapper.writeValueAsString(body);                // ⑧ 把 Java Map 序列化成 JSON 字符串

    Request request = new Request.Builder()                       // ⑨ 创建 HTTP 请求
            .url(apiUrl)                                          // ⑩ 请求地址
            .post(RequestBody.create(                             // ⑪ 使用 POST 方法，并设置请求体
                    json,                                         // ⑫ 请求体内容是 JSON
                    MediaType.parse("application/json")           // ⑬ Content-Type 是 application/json
            ))
            .build();                                             // ⑭ 构建 Request 对象

    try (Response response = httpClient.newCall(request).execute()) { // ⑮ 用 OkHttp 发起同步 HTTP 请求
        if (!response.isSuccessful()) {                           // ⑯ 如果 HTTP 状态码不是成功
            throw new RuntimeException("Tavily 返回错误状态 " + response.code()); // ⑰ 抛异常
        }

        String respBody = response.body() != null                 // ⑱ 读取响应 body
                ? response.body().string()
                : "";

        JsonNode root = mapper.readTree(respBody);                // ⑲ 把响应 JSON 解析成 JsonNode
        String answer = root.has("answer")                        // ⑳ 如果 Tavily 返回 answer 字段
                ? root.get("answer").asText("")                   // ㉑ 取 answer 文本
                : "";                                            // ㉒ 没有 answer 就用空字符串
        JsonNode results = root.get("results");                   // ㉓ 取 results 搜索结果数组

        if (!answer.isEmpty()) {                                  // ㉔ 如果 answer 不为空
            StringBuilder sb = new StringBuilder(answer);          // ㉕ 先把 answer 放入结果字符串
            if (results != null && results.isArray() && !results.isEmpty()) { // ㉖ 如果有来源结果
                sb.append("\n\n**来源：**\n");                    // ㉗ 添加来源标题
                int n = Math.min(3, results.size());              // ㉘ 最多展示 3 个来源
                for (int i = 0; i < n; i++) {                     // ㉙ 遍历前 3 个来源
                    JsonNode r = results.get(i);                  // ㉚ 取第 i 条结果
                    sb.append("- [")                              // ㉛ 拼接 Markdown 链接开头
                            .append(r.path("title").asText(""))   // ㉜ 拼接标题
                            .append("](")
                            .append(r.path("url").asText(""))     // ㉝ 拼接 URL
                            .append(")\n");
                }
            }
            return sb.toString();                                 // ㉞ 返回 answer + 来源
        }

        if (results == null || !results.isArray() || results.isEmpty()) { // ㉟ 如果没有 answer，也没有 results
            throw new RuntimeException("Tavily 返回空结果");        // ㊱ 抛异常，让上层 fallback
        }

        StringBuilder sb = new StringBuilder();                    // ㊲ 没有 answer，但有 results，就拼接 results
        int n = Math.min(3, results.size());                       // ㊳ 最多取 3 条
        for (int i = 0; i < n; i++) {                              // ㊴ 遍历结果
            JsonNode r = results.get(i);                           // ㊵ 取当前结果
            sb.append("**").append(r.path("title").asText("")).append("**\n"); // ㊶ 标题
            sb.append(r.path("content").asText("")).append("\n");  // ㊷ 摘要内容
            sb.append(r.path("url").asText("")).append("\n\n");    // ㊸ URL
        }
        return sb.toString().trim();                               // ㊹ 返回拼接后的搜索结果
    }
}
```

### 6.2 TavilyClient.search 逐步讲解

**第一步：确定请求地址**

```java
if (apiUrl == null || apiUrl.isEmpty()) {
    apiUrl = "https://api.tavily.com/search";
}
```

如果配置文件里没有写搜索 API 地址，就使用 Tavily 默认地址。

所以配置 Tavily 时，最关键的是 API key；API URL 可以不填。

**第二步：构造请求 body**

```java
body.put("api_key", apiKey);
body.put("query", query);
body.put("search_depth", "basic");
body.put("max_results", 5);
```

这一步把 Java 里的数据准备成 Tavily API 需要的字段。

假设用户问：

```text
查一下 Go 语言最新版本
```

请求 body 大概是：

```json
{
  "api_key": "你的 Tavily key",
  "query": "查一下 Go 语言最新版本",
  "search_depth": "basic",
  "max_results": 5
}
```

**第三步：Map 转 JSON**

```java
String json = mapper.writeValueAsString(body);
```

HTTP 请求不能直接发送 Java Map，所以要先把 Map 转成 JSON 字符串。

**第四步：构建 HTTP POST 请求**

```java
Request request = new Request.Builder()
        .url(apiUrl)
        .post(RequestBody.create(json, MediaType.parse("application/json")))
        .build();
```

这一段确定三件事：

```text
请求地址：apiUrl
请求方法：POST
请求内容：JSON
```

这就是“真的搜索”和 mock 的本质区别：这里创建了 HTTP 请求。

**第五步：发送 HTTP 请求**

```java
httpClient.newCall(request).execute()
```

`execute()` 会同步发起网络请求，等待 Tavily 返回结果。

如果网络不通、key 错误、Tavily 返回失败状态，后面会抛异常。

**第六步：检查状态码**

```java
if (!response.isSuccessful()) {
    throw new RuntimeException("Tavily 返回错误状态 " + response.code());
}
```

如果 Tavily 返回的不是成功状态码，就认为搜索失败。

这个异常会回到 `UnifiedAgentService.init()` 的搜索工具里，然后被：

```java
catch (Exception ignored) {}
```

吞掉，接着走 LLM fallback。

**第七步：解析 Tavily 返回 JSON**

```java
JsonNode root = mapper.readTree(respBody);
String answer = root.has("answer") ? root.get("answer").asText("") : "";
JsonNode results = root.get("results");
```

Tavily 可能返回两类重要数据：

```text
answer   → Tavily 总结出的直接答案
results  → 搜索结果列表，里面有 title、url、content
```

**第八步：优先返回 answer**

如果 Tavily 给了 `answer`，代码会优先返回它。

同时，如果 `results` 里有来源链接，会拼上最多 3 个来源。

最终工具结果类似：

```text
Go 最新稳定版本是 ...

**来源：**
- [Go Downloads](https://go.dev/dl/)
- [Go Release History](https://go.dev/doc/devel/release)
```

**第九步：没有 answer 时，拼接 results**

如果没有 `answer`，但有 `results`，代码会拼接前 3 条结果：

```text
标题
摘要
URL
```

这也是真实搜索结果，只是没有 Tavily 的直接答案字段。

**第十步：没有 answer 也没有 results，就抛异常**

```java
throw new RuntimeException("Tavily 返回空结果");
```

这个异常也会让上层走 LLM fallback。

## 7. 按“配置了 Tavily”完整跑一遍

假设用户输入：

```text
查一下 Go 语言最新版本
```

### 7.1 启动阶段

应用启动时先执行：

```java
tools.putAll(toolService.getDefaultTools());
```

此时：

```text
tools["search_web"] = mock 版 search_web
```

然后执行：

```java
tools.put("search_web", new Tool(... Tavily + LLM fallback ...));
```

此时：

```text
tools["search_web"] = Tavily + LLM fallback 版 search_web
```

因为两次 key 都是 `"search_web"`，后一次覆盖前一次。

### 7.2 决策阶段

进入 `ToolService.decide(...)`：

```java
String q = query.toLowerCase();
```

用户原句：

```text
查一下 Go 语言最新版本
```

小写匹配用的 `q`：

```text
查一下 go 语言最新版本
```

然后命中搜索规则：

```java
q.contains("查")
```

于是返回：

```java
new ToolCallResult(
        "search_web",
        Map.of("query", query)
)
```

得到：

```text
toolName = "search_web"
params = {
  "query": "查一下 Go 语言最新版本"
}
```

### 7.3 执行阶段

`ToolModeHandler` 会根据 `toolName` 取工具：

```text
tools.get("search_web")
```

因为启动阶段已经覆盖过，所以取到的是：

```text
Tavily + LLM fallback 版 search_web
```

不是 `ToolService.createSearchWebTool()` 里的 mock 版。

然后执行：

```java
tool.getExecute().apply(params)
```

进入覆盖后的 lambda：

```java
String q = params.get("query") != null
        ? params.get("query").toString()
        : "";
```

得到：

```text
q = "查一下 Go 语言最新版本"
```

### 7.4 Tavily 调用阶段

因为配置了 Tavily key，所以命中：

```java
if (cfg.getSearch().getApiKey() != null
        && !cfg.getSearch().getApiKey().isEmpty()) {
    return TavilyClient.search(q, cfg.getSearch().getApiKey(), cfg.getSearch().getApiUrl());
}
```

然后进入：

```java
TavilyClient.search(
        "查一下 Go 语言最新版本",
        "你的 Tavily API key",
        "https://api.tavily.com/search"
)
```

`TavilyClient.search` 会发 HTTP POST：

```text
POST https://api.tavily.com/search
Content-Type: application/json

{
  "api_key": "...",
  "query": "查一下 Go 语言最新版本",
  "search_depth": "basic",
  "max_results": 5
}
```

这一步是真的联网搜索。

### 7.5 返回阶段

Tavily 返回后，`TavilyClient.search` 会把结果整理成字符串。

可能是：

```text
Tavily answer

**来源：**
- [来源1](url1)
- [来源2](url2)
- [来源3](url3)
```

也可能是：

```text
**标题1**
摘要1
URL1

**标题2**
摘要2
URL2
```

这个字符串就是工具执行结果。

之后工具模式会把工具结果交给 LLM，让 LLM 基于搜索结果组织最终回答。

## 8. 三个版本不要混在一起

学习 `search_web` 时，必须把三层分清。

### 8.1 定义层：ToolService.createSearchWebTool

```text
作用：
  创建默认 search_web 工具

是否联网：
  不联网

返回内容：
  写死的 mock 文本

什么时候学它：
  学 Tool 是怎么定义的：name、description、params、execute
```

### 8.2 运行时层：UnifiedAgentService.init 覆盖 search_web

```text
作用：
  把默认 mock 版 search_web 覆盖成真实运行版

是否联网：
  取决于是否配置 Tavily key 以及 Tavily 是否调用成功

关键点：
  同一个 key 再 put 一次，会覆盖旧 Tool
```

### 8.3 HTTP 层：TavilyClient.search

```text
作用：
  真正向 Tavily API 发 HTTP POST

是否联网：
  是

关键点：
  构造 JSON body
  OkHttp 发送请求
  解析 answer/results
  返回整理后的搜索结果字符串
```

## 9. 常见误解

**误解一：`ToolService.createSearchWebTool()` 就是真实搜索。**

不是。它只是默认 mock 版，不会联网。

真实联网发生在：

```java
TavilyClient.search(...)
```

而这段调用是在 `UnifiedAgentService.init()` 覆盖后的 `search_web` 工具里。

**误解二：没配置 Tavily key 时，会自动回到 ToolService 的 mock 版。**

不会。

正常应用启动时，`UnifiedAgentService.init()` 已经覆盖了 `search_web`。

没配置 key 时，执行的是覆盖后工具里的：

```java
return llm.chat(...);
```

也就是 LLM fallback，不是 mock 版。

**误解三：Tavily 失败时会返回 mock 搜索结果。**

不会。

Tavily 失败后会被：

```java
catch (Exception ignored) {}
```

吞掉，然后继续执行下面的 `llm.chat(...)` fallback。

**误解四：`search_web` 有两个工具同时存在。**

不是同时存在。

Map 里同一个 key 只能对应一个值：

```text
tools["search_web"]
```

后一次 `put("search_web", ...)` 会覆盖前一次。

**误解五：`query` 会被自动改写成关键词。**

不会。

`ToolService.decide(...)` 返回的是：

```java
Map.of("query", query)
```

这里放的是用户原始问题。

如果要做搜索 query 改写，比如把：

```text
查一下 Go 语言最新版本
```

改成：

```text
Go latest stable version site:go.dev
```

那需要额外写 query rewrite 逻辑。当前这段代码没有做。

## 10. 本篇小结

这一篇你要掌握四个结论：

```text
1. ToolService.createSearchWebTool()
   创建的是默认 mock 版 search_web，不联网。

2. ToolService.decide(...)
   看到“查 / 搜索 / 是什么”后，会决策出：
   ToolCallResult("search_web", {"query": 用户原句})

3. UnifiedAgentService.init()
   会用同名 key 覆盖 search_web。
   所以正常运行时拿到的是 Tavily + LLM fallback 版。

4. 配置 Tavily key 且 Tavily 调用成功时，
   TavilyClient.search(...) 会通过 OkHttp 发 HTTP POST，
   这才是真正的联网搜索。
```
