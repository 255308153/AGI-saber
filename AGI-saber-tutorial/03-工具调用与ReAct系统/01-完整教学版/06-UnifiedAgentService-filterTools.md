# 06-UnifiedAgentService-filterTools

## 1. 这个方法解决什么问题

前端可以让用户勾选工具——比如只选 `get_weather`，不选 `get_time` 和 `search_web`。这时后端不能直接用全量工具库，需要用 `filterTools` 裁剪出一个子集。

同时它还承担一个安全职责：**前端传来的工具名不可信**。万一前端传了 `["get_weather", "drop_database"]`，`filterTools` 只会保留真实存在的工具，不存在的直接丢弃。

## 2. 方法源码，源码里加注释

```java
/**
 * 位置：UnifiedAgentService.java:433-439
 *
 * 参数：names - 前端传来的工具名列表（可能包含不存在的名字）
 * 返回：裁剪后的工具子集（只包含 names 和 tools 的交集）
 */
private Map<String, Tool> filterTools(List<String> names) {
    Map<String, Tool> result = new java.util.HashMap<>();       // ① 新建空 Map
    for (String name : names) {                                  // ② 遍历前端列表
        if (tools.containsKey(name))                             // ③ 只在全局 tools 中存在的才加进去
            result.put(name, tools.get(name));
    }
    return result;                                               // ④ 返回子集
}
```
### 2.1 逐行解释

下面按照源码里的编号，把这个方法的每一步拆开说明。重点看每一步改变了什么数据、影响了哪个后续方法。

**① 新建空 Map**

新建空 Map。这一步先准备容器或对象，后面的循环、写入或返回都依赖它。

**② 遍历前端列表**

遍历前端列表。方法通过遍历集合逐个处理候选项，而不是一次性假设所有输入都有效。

**③ 只在全局 tools 中存在的才加进去**

只在全局 tools 中存在的才加进去。这里是保护条件，目的是避免后面的执行逻辑拿到空对象、错误工具或不满足条件的数据。

**④ 返回子集**

返回子集。这一行会直接结束当前方法或结束当前分支，所以它决定了调用方下一步能拿到什么值。


## 3. 参数逐个解释

| 参数 | 类型 | 含义 |
|---|---|---|
| `names` | `List<String>` | 前端勾选的工具名列表，如 `["get_weather", "get_time"]` |

`names` 来自 `ChatRequest.getSelectedTools()`，由前端传过来。它可以是空列表（用户没选），也可以包含不存在的工具名。

## 4. 返回值/副作用解释

**返回值**：`Map<String, Tool>`——裁剪后的工具子集。

**副作用**：无。

**三种返回值情况**：

| names 内容 | tools 中存在 | 返回值 | 后续行为 |
|---|---|---|---|
| `["get_weather"]` | 是 | `{get_weather: Tool}` | toolset = 过滤结果 |
| `["get_weather", "fake_tool"]` | 部分 | `{get_weather: Tool}` | fake_tool 被静默丢弃 |
| `["fake_tool"]` | 全不存在 | `{}`（空 Map） | mode 被强设为 `"chat"` |

**第三种情况是关键**。`filterTools` 返回空 Map 后，`processInternal` 里的这段代码生效：

```java
if (!filtered.isEmpty()) {
    toolset = filtered;
} else {
    mode = "chat";   // 降级
}
```

降级到 chat 而不报错，是因为给用户看"工具 fake_tool 不存在"没有意义——这不是用户的错，可能是前端 bug 或配置问题。

## 5. 这一步在完整链路中的位置

```text
processInternal
  ├── decideMode → mode
  ├── if (explicit && selectedTools 非空):
  │     filterTools(names)  ← 你在这里
  │     if 结果为空 → mode = "chat"
  │     else → toolset = 结果
  ├── switch(mode) 用 toolset 执行
  └── ...
```

**`filterTools` 只在 `explicit=true` 且 `selectedTools` 非空时调用**。自动模式下不会调用——`toolset = tools`（全量）。

## 6. 用例子跑一遍

**例子 A：正常过滤**

```text
tools = {get_time, get_weather, search_web, rag_search, exec_command}
names = ["get_weather", "get_time"]

filterTools(names):
  "get_weather" → tools.containsKey → ✓ → result.put
  "get_time"    → tools.containsKey → ✓ → result.put

返回值 = {get_weather: Tool@123, get_time: Tool@456}
toolset 变成这个子集
→ ToolService.decide 只能在这两个工具中选
```

**例子 B：包含不存在的工具名，降级**

```text
names = ["nonexistent_tool"]

filterTools(names):
  "nonexistent_tool" → tools.containsKey → ✗ → 跳过

返回值 = {}（空 Map）
→ filtered.isEmpty() = true
→ mode = "chat"
→ 走 switch 的 default 分支，LLM 直接回答
```

## 7. 常见误解

**误解一："filterTools 会修改全局 tools"**

不会。它新建了一个 `HashMap`，只在其中放入存在的工具。全局 `this.tools`（`ConcurrentHashMap`）完全不受影响。

**误解二："前端传什么工具名，后端就用什么"**

通过 `filterTools` 的 `containsKey` 检查，不存在的工具名被静默丢弃。这是一种防御性设计——前端可能因为版本不同步传了一个已删除的工具名。

**误解三："filterTools 的过滤结果会传给 decideMode"**

不会。`filterTools` 在 `decideMode` **之后**调用。顺序是：先决定 mode，再裁剪 toolset。裁剪只影响"用哪些工具执行"，不影响"走什么模式"。

**误解四："过滤后的 toolset 是永久性的"**

不是。`toolset` 是 `processInternal` 方法内的局部变量，存活范围仅限这一次请求。下一轮请求进来时，`toolset` 重新从 `tools`（全量）初始化。
