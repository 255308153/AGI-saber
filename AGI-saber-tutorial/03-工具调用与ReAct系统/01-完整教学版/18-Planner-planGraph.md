# 18-Planner-planGraph

## 1. 这个方法解决什么问题

ReAct 的第一步：把用户 query 拆成一组可执行的任务节点。`planGraph` 是 LLM-based 的规划器——它把工具列表和子 Agent 列表喂给 LLM，让 LLM 输出带依赖关系和竞速组的 JSON 节点列表。

**Planner 只负责规划，不负责执行**。它产出的 `List<Node>` 交给 `TaskGraph` 和 `GraphRuntime` 去执行。

## 2. 方法源码，源码里加注释

````java
/**
 * 位置：Planner.java:167-342
 * 返回：List<Node> — 空列表 = "无需工具"，非空 = 规划出的节点
 */
public List<Node> planGraph(String query, Map<String, Tool> ts, String memPrefix) {
    String qLower = query == null ? "" : query.toLowerCase();
    // ① sub-agent 关键词 → 直接规则规划（不用 LLM 拆）
    if (needsSubAgentPlan(qLower)) return rulePlanNodes(query, ts);
    // ② mock 模式 → 规则规划
    if (!cfg.isRealLLM()) return rulePlanNodes(query, ts);

    // ③ 拼装工具描述文本（供 LLM prompt）
    StringBuilder toolLines = new StringBuilder();
    for (Map.Entry<String, Tool> e : ts.entrySet()) {
        String name = e.getKey();
        Tool t = e.getValue();
        StringBuilder pDescs = new StringBuilder();
        if (t.getParameters() != null) {
            for (ToolParam p : t.getParameters()) {
                if (pDescs.length() > 0) pDescs.append(", ");
                pDescs.append(p.getName()).append("(").append(p.getType()).append(")");
                if (p.isRequired()) pDescs.append("（必填）");
            }
        }
        toolLines.append("- ").append(name).append(": ").append(t.getDescription())
                .append(" [参数: ").append(pDescs.length() > 0 ? pDescs : "无").append("]\n");
    }

    // ④ 拼装子 Agent 描述
    StringBuilder agentLines = new StringBuilder();
    if (subAgents != null) {
        for (Map.Entry<String, SubAgent> e : subAgents.snapshot().entrySet()) {
            agentLines.append("- ").append(e.getKey()).append(": ")
                    .append(e.getValue().description()).append("\n");
        }
    }

    // ⑤ 构造 LLM prompt（期望输出 DAG 格式 JSON）
    String planPrompt = String.format("""
            你是一个任务规划器。根据用户问题，从可用工具和可用子 Agent 中选出需要调用的节点，并标注它们之间的依赖关系。
            规则：
            - 给每个节点分配一个唯一 id（如 n1, n2, n3...）
            - type 只能是 "tool" 或 "sub_agent"
            - 工具节点填写 tool 和 params；子 Agent 节点填写 agent 和 goal
            - 如果节点 B 需要节点 A 的输出，则 B 的 depends_on 包含 A 的 id
            - 如果两个工具功能类似（如多个搜索源），设相同的 race_group，系统会并行执行谁先返回用谁
            - 无依赖关系的节点不要互相等待，depends_on 设为 []

            用户问题：%s
            可用工具：
            %s

            可用子 Agent：
            %s

            请以 JSON 数组格式输出执行计划：
            [{"id":"n1","type":"sub_agent","agent":"research_agent","goal":"研究目标","params":{},"reason":"一句话说明为什么调用","depends_on":[],"race_group":""}]
            如果无需工具直接回答，输出 []。只输出 JSON，不要其他内容.""",
            query, toolLines, agentLines.length() == 0 ? "（无）" : agentLines);

    String plannerBase = "你是一个精准的任务规划器，只在必要时才调用工具，不做无意义的调用。能识别工具间的依赖关系和可竞速的同类工具。";
    if (memPrefix != null && !memPrefix.isEmpty()) {
        plannerBase = memPrefix + "\n\n" + plannerBase
                + "\n注意：用户偏好可能影响工具参数选择（如城市、时区等），请在参数中体现。";
    }

    // ⑥ 调用 LLM
    String raw = llm.chat(plannerBase, List.of(Map.of("role", "user", "content", planPrompt)));
    if (raw == null) return rulePlanNodes(query, ts);

    // ⑦ 清洗输出：去掉 function-calling wrapper、markdown fence
    raw = raw.trim();
    int begin = raw.indexOf("<|FunctionCallBegin|>");
    if (begin >= 0) {
        raw = raw.substring(begin + "<|FunctionCallBegin|>".length());
        int end = raw.indexOf("<|FunctionCallEnd|>");
        if (end >= 0) raw = raw.substring(0, end);
    }
    raw = raw.replace("```json", "").replace("```", "").trim();

    // ⑧ 解析格式 1（新 DAG 格式，带 type/depends_on/race_group）
    try {
        List<Map<String, Object>> items = M.readValue(raw,
                M.getTypeFactory().constructCollectionType(List.class, Map.class));
        List<Node> result = new ArrayList<>();
        int counter = 0;
        for (Map<String, Object> item : items) {
            counter++;
            String type = item.get("type") == null ? "" : item.get("type").toString();
            String tool = item.get("tool") == null ? "" : item.get("tool").toString();
            String agent = item.get("agent") == null ? "" : item.get("agent").toString();

            NodeType nodeType;
            if (type.isEmpty()) {
                nodeType = agent.isEmpty() ? NodeType.TOOL : NodeType.SUB_AGENT;
            } else {
                nodeType = NodeType.fromValue(type);
            }

            if (nodeType == NodeType.SUB_AGENT) {
                if (subAgents == null || !subAgents.has(agent)) continue;
            } else {
                if (tool.isEmpty() || !ts.containsKey(tool)) continue;
            }

            String id = item.get("id") != null && !item.get("id").toString().isEmpty()
                    ? item.get("id").toString() : "n" + counter;

            Map<String, String> params = new HashMap<>();
            Object rawParams = item.get("params");
            if (rawParams instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    if (en.getKey() != null) params.put(en.getKey().toString(),
                            en.getValue() == null ? "" : en.getValue().toString());
                }
            }

            String reason = item.get("reason") != null ? item.get("reason").toString() : "";
            String goal = item.get("goal") != null ? item.get("goal").toString() : reason;
            String name = reason.isEmpty() ? goal : reason;

            List<String> deps = new ArrayList<>();
            Object rawDeps = item.get("depends_on");
            if (rawDeps instanceof List<?> ld) {
                for (Object o : ld) if (o != null) deps.add(o.toString());
            }

            String rg = item.get("race_group") != null ? item.get("race_group").toString() : "";

            if (nodeType == NodeType.SUB_AGENT) {
                result.add(Node.subAgent(id, name, agent, goal, deps, rg));
            } else {
                result.add(new Node(id, NodeType.TOOL, name, tool, params, deps, rg));
            }
        }
        if (!result.isEmpty()) return result;
    } catch (Exception ignored) { /* fall through */ }

    // ⑨ 解析格式 2（旧格式 [{"tool","params","reason"}] — 全并行）
    try {
        List<Map<String, Object>> items = M.readValue(raw,
                M.getTypeFactory().constructCollectionType(List.class, Map.class));
        List<Node> result = new ArrayList<>();
        int counter = 0;
        for (Map<String, Object> item : items) {
            String tool = (String) item.get("tool");
            if (tool == null || !ts.containsKey(tool)) continue;
            counter++;

            Map<String, String> params = new HashMap<>();
            Object rawParams = item.get("params");
            if (rawParams instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    if (en.getKey() != null) params.put(en.getKey().toString(),
                            en.getValue() == null ? "" : en.getValue().toString());
                }
            }

            String reason = item.get("reason") != null
                    ? item.get("reason").toString() : "调用 " + tool;
            result.add(new Node("n" + counter, NodeType.TOOL, reason, tool, params,
                    new ArrayList<>(), ""));
        }
        if (!result.isEmpty()) return result;
    } catch (Exception ignored) { /* fall through */ }

    // ⑩ 解析格式 3（function-calling [{"name","parameters"}] — 全并行）
    try {
        List<Map<String, Object>> items = M.readValue(raw,
                M.getTypeFactory().constructCollectionType(List.class, Map.class));
        List<Node> result = new ArrayList<>();
        int counter = 0;
        for (Map<String, Object> item : items) {
            String tool = (String) item.get("name");
            if (tool == null || !ts.containsKey(tool)) continue;
            counter++;

            Map<String, String> params = new HashMap<>();
            Object rawParams = item.get("parameters");
            if (rawParams instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    if (en.getKey() != null) params.put(en.getKey().toString(),
                            en.getValue() == null ? "" : en.getValue().toString());
                }
            }

            result.add(new Node("n" + counter, NodeType.TOOL, "LLM 规划调用", tool,
                    params, new ArrayList<>(), ""));
        }
        if (!result.isEmpty()) return result;
    } catch (Exception ignored) { /* fall through */ }

    // ⑪ 全部解析失败 → 降级规则规划
    log.warn("Planner LLM 图规划解析失败，降级为规则规划。原始输出: {}", raw);
    return rulePlanNodes(query, ts);
}
````
### 2.1 逐行解释

下面按源码里的编号，把 `planGraph` 真正做的事情拆开。先记住一句话：这个方法的输入是用户问题和工具表，输出不是答案，而是一组 `Node`。这些 `Node` 随后会被 `TaskGraph` 建图，再被 `GraphRuntime` 执行。

**① sub-agent 关键词 → 直接规则规划（不用 LLM 拆）**

源码先把用户问题转小写：

```java
String qLower = query == null ? "" : query.toLowerCase();
```

然后判断：

```java
if (needsSubAgentPlan(qLower)) return rulePlanNodes(query, ts);
```

`needsSubAgentPlan` 看的是这些关键词：

```text
研究 / 调研 / 总结 / 报告 / 文档 / 方案 / 分析
```

只要命中，就不让 LLM 自由规划，而是直接走 `rulePlanNodes`。原因是这类任务通常应该走固定子 Agent 链：

```text
research_agent → writer_agent → review_agent → 可选 doc_agent
```

例如用户说：

```text
帮我调研一下 AI Agent 框架并生成报告
```

直接规则规划会更稳定：

```text
n1 research_agent  收集资料
n2 writer_agent    依赖 n1，生成报告
n3 review_agent    依赖 n2，审查报告
n4 doc_agent       依赖 n2/n3，保存文档
```

所以这一步是一个“强制走子 Agent 链”的入口，不是执行子 Agent。

**② mock 模式 → 规则规划**

第二个提前返回：

```java
if (!cfg.isRealLLM()) return rulePlanNodes(query, ts);
```

如果当前配置不是 `realLLM`，就不能依赖模型输出 JSON，所以直接用关键词规则规划。这样教学、测试、mock 环境也能跑通。

到这里有两个分支会跳过 LLM：

```text
命中研究/报告/文档类关键词 → rulePlanNodes
没有真实 LLM                 → rulePlanNodes
```

只有两个条件都不满足，才会继续构造 prompt 调 LLM。

**③ 拼装工具描述文本（供 LLM prompt）**

这里遍历 `ts` 这张工具表：

```java
for (Map.Entry<String, Tool> e : ts.entrySet()) {
    String name = e.getKey();
    Tool t = e.getValue();
    ...
}
```

`ts` 的 key 是工具名，value 是 `Tool` 对象，例如：

```text
get_time    → Tool(...)
get_weather → Tool(...)
search_web  → Tool(...)
rag_search  → Tool(...)
```

对每个工具，代码会读取：

```java
t.getDescription()
t.getParameters()
```

然后把参数整理成一行文本。比如 `get_weather` 的 `ToolParam` 是：

```text
city(string)（必填）
```

最终拼出来的 `toolLines` 大概长这样：

```text
- get_time: 获取当前时间 [参数: timezone(string)]
- get_weather: 获取城市天气信息 [参数: city(string)（必填）]
- search_web: 搜索互联网获取最新信息 [参数: query(string)（必填）]
- rag_search: 从私人黑洞（个人知识库）中检索相关文档内容 [参数: query(string)（必填）]
```

这段文本会放进 prompt，让 LLM 知道“有哪些执行体可以选、每个执行体要什么参数”。

注意：这一步也不执行工具，只是把工具说明写成 prompt 材料。

**④ 拼装子 Agent 描述**

如果构造 `Planner` 时传入了 `SubAgentRegistry`，这里会把已注册的子 Agent 也写进 prompt：

```java
if (subAgents != null) {
    for (Map.Entry<String, SubAgent> e : subAgents.snapshot().entrySet()) {
        agentLines.append("- ").append(e.getKey()).append(": ")
                .append(e.getValue().description()).append("\n");
    }
}
```

生成的 `agentLines` 类似：

```text
- research_agent: 多轮检索和证据整理
- writer_agent: 基于上游结果生成 Markdown 报告
- review_agent: 检查报告质量、风险和证据缺口
- doc_agent: 保存文档到本地文档库并写入 RAG
```

LLM 看到这些描述后，才可能输出：

```json
{"type":"sub_agent","agent":"writer_agent","goal":"生成报告"}
```

如果 `subAgents == null`，prompt 里会写：

```text
可用子 Agent：
（无）
```

这时模型理论上就不应该规划子 Agent 节点。

**⑤ 构造 LLM prompt（期望输出 DAG 格式 JSON）**

`planPrompt` 是给 LLM 的用户消息。它包含四类信息：

```text
1. 你是任务规划器
2. 规划规则
3. 用户问题
4. 可用工具和可用子 Agent
```

最关键的是规则部分：

```text
- 给每个节点分配唯一 id，如 n1/n2/n3
- type 只能是 "tool" 或 "sub_agent"
- 工具节点填写 tool 和 params
- 子 Agent 节点填写 agent 和 goal
- 如果 B 需要 A 的输出，B.depends_on 包含 A 的 id
- 类似工具可以设置相同 race_group，执行阶段会竞速
- 无依赖就 depends_on=[]
```

也就是说，Planner 希望 LLM 不只是说“调用哪些工具”，还要说：

```text
谁先跑
谁依赖谁
哪些节点可以并行
哪些节点属于竞速组
```

这就是为什么这里叫 `planGraph`，不是旧版的 `plan`。旧版只是一串工具调用列表，新版要规划一张 DAG。

另外还有一个 `plannerBase` 作为 system prompt：

```java
String plannerBase = "你是一个精准的任务规划器...";
```

如果 `memPrefix` 不为空，会被拼到 system prompt 前面：

```java
plannerBase = memPrefix + "\n\n" + plannerBase
        + "\n注意：用户偏好可能影响工具参数选择...";
```

例如用户偏好里有：

```text
常住城市：上海
```

用户只问：

```text
今天空气怎么样
```

模型就可能把 `city` 填成上海。这就是 `memPrefix` 在规划阶段的作用。

**⑥ 调用 LLM**

真正调用模型的是这一行：

```java
String raw = llm.chat(plannerBase,
        List.of(Map.of("role", "user", "content", planPrompt)));
```

返回的 `raw` 是模型输出的原始字符串。理想情况下是 JSON 数组：

```json
[
  {
    "id": "n1",
    "type": "tool",
    "tool": "get_weather",
    "params": {"city": "上海"},
    "reason": "查询上海天气",
    "depends_on": [],
    "race_group": ""
  }
]
```

如果 `raw == null`，说明 LLM 没给出可用输出，直接降级：

```java
if (raw == null) return rulePlanNodes(query, ts);
```

从这里开始，代码做的是一次数据变形：

```text
LLM 原始字符串 raw
  → 清洗成 JSON 数组字符串
  → ObjectMapper 解析成 List<Map<String,Object>>
  → 每个 Map 转成完整 Node
  → 返回 List<Node>
```

**⑦ 清洗输出：把外包装去掉，只留下 JSON 数组**

清洗代码：

```java
raw = raw.trim();
int begin = raw.indexOf("<|FunctionCallBegin|>");
if (begin >= 0) {
    raw = raw.substring(begin + "<|FunctionCallBegin|>".length());
    int end = raw.indexOf("<|FunctionCallEnd|>");
    if (end >= 0) raw = raw.substring(0, end);
}
raw = raw.replace("```json", "").replace("```", "").trim();
```

例子 A：Markdown 包裹。

```text
清洗前 raw：
开头：3 个反引号加 json
[
  {
    "id": "n1",
    "type": "tool",
    "tool": "get_weather",
    "params": {"city": "上海"},
    "reason": "查询上海天气",
    "depends_on": [],
    "race_group": ""
  }
]
结尾：3 个反引号

清洗后 raw：
[
  {
    "id": "n1",
    "type": "tool",
    "tool": "get_weather",
    "params": {"city": "上海"},
    "reason": "查询上海天气",
    "depends_on": [],
    "race_group": ""
  }
]
```

例子 B：function-calling 包裹。

```text
清洗前 raw：
模型说明文字
<|FunctionCallBegin|>
[
  {
    "name": "search_web",
    "parameters": {"query": "小雨出门建议"}
  }
]
<|FunctionCallEnd|>
模型额外输出的废话

清洗后 raw：
[
  {
    "name": "search_web",
    "parameters": {"query": "小雨出门建议"}
  }
]
```

这一步结束后，`raw` 仍然是 `String`，只是内容已经变成 JSON 数组文本。

**⑧ 解析格式 1：新 DAG JSON → `List<Map<String,Object>>` → 完整 `Node`**

新 DAG 格式是最完整的格式，能表达工具、子 Agent、依赖和竞速。

清洗后的 JSON 数组：

```json
[
  {
    "id": "n1",
    "type": "tool",
    "tool": "get_weather",
    "agent": "",
    "goal": "",
    "params": {
      "city": "上海"
    },
    "reason": "查询上海天气",
    "depends_on": [],
    "race_group": ""
  },
  {
    "id": "n2",
    "type": "tool",
    "tool": "search_web",
    "agent": "",
    "goal": "",
    "params": {
      "query": "小雨出门建议"
    },
    "reason": "搜索雨天出行建议",
    "depends_on": ["n1"],
    "race_group": "search"
  }
]
```

先被 `ObjectMapper` 解析成：

```java
List<Map<String, Object>> items = M.readValue(raw,
        M.getTypeFactory().constructCollectionType(List.class, Map.class));
```

此时 `items` 的完整结构可以理解为：

```text
items = [
  item0 = {
    "id"         -> "n1",
    "type"       -> "tool",
    "tool"       -> "get_weather",
    "agent"      -> "",
    "goal"       -> "",
    "params"     -> {
      "city" -> "上海"
    },
    "reason"     -> "查询上海天气",
    "depends_on" -> [],
    "race_group" -> ""
  },
  item1 = {
    "id"         -> "n2",
    "type"       -> "tool",
    "tool"       -> "search_web",
    "agent"      -> "",
    "goal"       -> "",
    "params"     -> {
      "query" -> "小雨出门建议"
    },
    "reason"     -> "搜索雨天出行建议",
    "depends_on" -> ["n1"],
    "race_group" -> "search"
  }
]
```

然后每个 `item` 转成一个完整 `Node`。第一个 `item` 转换后是：

```java
Node {
    id = "n1",
    type = NodeType.TOOL,
    name = "查询上海天气",
    toolName = "get_weather",
    agentName = "",
    goal = "",
    params = {
        "city": "上海"
    },
    dependsOn = [],
    raceGroup = "",
    status = NodeStatus.PENDING,
    result = "",
    error = "",
    retryCount = 0
}
```

第二个 `item` 转换后是：

```java
Node {
    id = "n2",
    type = NodeType.TOOL,
    name = "搜索雨天出行建议",
    toolName = "search_web",
    agentName = "",
    goal = "",
    params = {
        "query": "小雨出门建议"
    },
    dependsOn = ["n1"],
    raceGroup = "search",
    status = NodeStatus.PENDING,
    result = "",
    error = "",
    retryCount = 0
}
```

字段映射关系：

| JSON / Map 字段 | Node 字段 | 说明 |
|---|---|---|
| `id` | `id` | 节点 ID；没给就自动补 `n1/n2/...` |
| `type` | `type` | `"tool"` → `NodeType.TOOL`，`"sub_agent"` → `NodeType.SUB_AGENT` |
| `reason` | `name` | 节点展示名；如果没有 `reason`，就用 `goal` |
| `tool` | `toolName` | 工具节点执行哪个工具 |
| `agent` | `agentName` | 子 Agent 节点执行哪个 Agent |
| `goal` | `goal` | 子 Agent 的任务目标；没有就用 `reason` 兜底 |
| `params` | `params` | 工具参数，统一转成 `Map<String,String>` |
| `depends_on` | `dependsOn` | 依赖哪些节点的输出 |
| `race_group` | `raceGroup` | 同组节点在执行阶段可以竞速 |
| 无 | `status` | 初始值 `PENDING`，执行时更新 |
| 无 | `result` | 初始值空字符串，执行成功后写结果 |
| 无 | `error` | 初始值空字符串，执行失败后写错误 |
| 无 | `retryCount` | 初始值 `0`，重试时增加 |

如果是子 Agent JSON：

```json
{
  "id": "n3",
  "type": "sub_agent",
  "tool": "",
  "agent": "writer_agent",
  "goal": "基于天气和搜索结果生成出行建议",
  "params": {},
  "reason": "生成最终建议",
  "depends_on": ["n1", "n2"],
  "race_group": ""
}
```

转换后是：

```java
Node {
    id = "n3",
    type = NodeType.SUB_AGENT,
    name = "生成最终建议",
    toolName = null,
    agentName = "writer_agent",
    goal = "基于天气和搜索结果生成出行建议",
    params = {},
    dependsOn = ["n1", "n2"],
    raceGroup = "",
    status = NodeStatus.PENDING,
    result = "",
    error = "",
    retryCount = 0
}
```

注意两点：

```text
1. LLM 可能胡编工具名或 Agent 名；不存在的执行体会被 continue 跳过。
2. 只要新格式成功解析出至少一个 Node，就直接 return result，不再尝试旧格式。
```

**⑨ 解析格式 2：旧工具 JSON → 全并行 TOOL 节点**

旧格式只有工具名、参数和原因：

```json
[
  {
    "tool": "get_weather",
    "params": {
      "city": "上海"
    },
    "reason": "查询上海天气"
  }
]
```

解析成 `items` 后：

```text
items = [
  item0 = {
    "tool"   -> "get_weather",
    "params" -> {
      "city" -> "上海"
    },
    "reason" -> "查询上海天气"
  }
]
```

转换后的完整 `Node`：

```java
Node {
    id = "n1",
    type = NodeType.TOOL,
    name = "查询上海天气",
    toolName = "get_weather",
    agentName = "",
    goal = "",
    params = {
        "city": "上海"
    },
    dependsOn = [],
    raceGroup = "",
    status = NodeStatus.PENDING,
    result = "",
    error = "",
    retryCount = 0
}
```

旧格式没有 `id/type/depends_on/race_group/agent/goal`，所以代码自动补：

```text
id        = "n" + counter
type      = NodeType.TOOL
dependsOn = []
raceGroup = ""
agentName = ""
goal      = ""
```

因此旧格式能执行，但表达不了依赖和竞速，所有节点默认全并行。

**⑩ 解析格式 3：function-calling JSON → 全并行 TOOL 节点**

function-calling 格式字段名不同：

```json
[
  {
    "name": "search_web",
    "parameters": {
      "query": "小雨出门建议"
    }
  }
]
```

解析成 `items` 后：

```text
items = [
  item0 = {
    "name"       -> "search_web",
    "parameters" -> {
      "query" -> "小雨出门建议"
    }
  }
]
```

转换后的完整 `Node`：

```java
Node {
    id = "n1",
    type = NodeType.TOOL,
    name = "LLM 规划调用",
    toolName = "search_web",
    agentName = "",
    goal = "",
    params = {
        "query": "小雨出门建议"
    },
    dependsOn = [],
    raceGroup = "",
    status = NodeStatus.PENDING,
    result = "",
    error = "",
    retryCount = 0
}
```

这里的字段映射是：

```text
name       → toolName
parameters → params
```

其他字段同样由代码补默认值。

**⑪ 全部解析失败 → 降级规则规划**

如果三种格式都解析不出可执行节点，就走规则规划：

```java
log.warn("Planner LLM 图规划解析失败，降级为规则规划。原始输出: {}", raw);
return rulePlanNodes(query, ts);
```

这时失败可能来自：

```text
raw 不是合法 JSON
raw 是 JSON，但不是数组
raw 是数组，但字段名不符合三种格式
raw 里工具名 / Agent 名都不存在，result 一直为空
```

最终完整降级顺序是：

```text
命中 sub-agent 关键词       → rulePlanNodes
没有真实 LLM                → rulePlanNodes
LLM 返回 null               → rulePlanNodes
新 DAG 格式解析成功          → 返回 DAG Node
新 DAG 格式失败
  → 旧 tool 格式成功         → 返回全并行 TOOL Node
旧 tool 格式失败
  → function-call 格式成功   → 返回全并行 TOOL Node
全部失败                    → rulePlanNodes
```


## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `query` | `String` | 用户原始输入 |
| `ts` | `Map<String, Tool>` | 本轮可用工具集 |
| `memPrefix` | `String` | 记忆前缀（偏好），注入 LLM prompt 用于个性化参数选择 |

## 4. 返回值/副作用解释

**返回值**：`List<Node>`

- 空列表 → Planner 判断"无需工具"→ 调用方降级纯 chat
- 非空 → 进入 `TaskGraph` 构建和 `GraphRuntime` 执行

**副作用**：不修改 `ts`、不执行工具、不写 `resp`。但在真实 LLM 模式下会调用一次 `llm.chat`；在规则规划分支不会调用 LLM。

**降级链路（4 级）**：

```text
LLM 调用成功
  → 格式1解析成功 → 返回 DAG 节点（最优：有依赖和竞速）
  → 格式1失败 → 格式2成功 → 返回全并行节点
  → 格式2失败 → 格式3成功 → 返回全并行节点
LLM 调用失败 / 全部解析失败
  → rulePlanNodes (第19章)
```

## 5. 位置

```text
ReActLoop.runStream
  ├── ① planner.planGraph → List<Node>  ← 你在这里
  ├── ② new TaskGraph(nodes)
  ├── ③ GraphRuntime.execute
  └── ④ ChatGenerator.generate
```

## 6. 用例子跑一遍

```text
query = "查一下上海天气，并搜索小雨出门建议"
ts = {get_time, get_weather, search_web, rag_search}

① LLM prompt 包含：
   工具：get_time(参数:timezone), get_weather(参数:city(必填)), ...
   query: "查一下上海天气，并搜索小雨出门建议"

② LLM 输出：
[
  {"id":"n1","type":"tool","tool":"get_weather",
   "params":{"city":"上海"},"reason":"查询上海天气",
   "depends_on":[],"race_group":""},
  {"id":"n2","type":"tool","tool":"search_web",
   "params":{"query":"小雨出门建议"},"reason":"搜索雨天出行建议",
   "depends_on":["n1"],"race_group":"search"},
  {"id":"n3","type":"tool","tool":"rag_search",
   "params":{"query":"小雨出门建议"},"reason":"从知识库搜索建议",
   "depends_on":["n1"],"race_group":"search"}
]

③ 格式1解析成功 → 3个Node：
   Node(id=n1, type=TOOL, toolName=get_weather,
        params={city=上海}, dependsOn=[], raceGroup="")
   Node(id=n2, type=TOOL, toolName=search_web,
        params={query=小雨出门建议}, dependsOn=[n1], raceGroup="search")
   Node(id=n3, type=TOOL, toolName=rag_search,
        params={query=小雨出门建议}, dependsOn=[n1], raceGroup="search")

④ 返回 List<Node> 给 ReActLoop.runStream
```

## 7. 常见误解

**误解一："planGraph 执行工具"**

不执行。它只规划"要执行哪些节点"。执行由 `GraphRuntime.invoke`（第 29 章）完成。

**误解二："LLM 输出格式永远是新的 DAG 格式"**

不。三个格式解析依次尝试——兼容了 LLM 可能输出的任何格式。这是防御性设计：LLM 的输出格式不可靠。

**误解三："planGraph 的 LLM prompt 不需要 ToolParam"**

需要。LLM 通过 `ToolParam` 的 `description` 和 `required` 判断该传什么参数。比如看到 `city(必填)`，LLM 就知道必须从 query 提取城市名。
