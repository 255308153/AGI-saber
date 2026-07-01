---
name: source-code-tutorial-writer
description: Use when writing, rewriting, or improving beginner-friendly technical tutorial documents based on real source code. Trigger when the user asks to explain code into documentation, rewrite a tutorial section, make an explanation more concrete, describe execution flow, parsing logic, data structures, runtime behavior, function/tool calls, events, state changes, or input/output transformations.
---

# Source Code Tutorial Writer

Use this skill when the user wants to turn real project source code into beginner-friendly tutorial documentation.

This skill is for writing teaching documents, not marketing docs, API reference docs, or vague architecture summaries.

The document should help a beginner understand:

```text
这段代码在哪里
它解决什么问题
谁调用它
输入是什么
中间数据怎么变
调用了谁
状态怎么变
输出是什么
失败时怎么办
```

## Core Rule

Always read the real source code before writing.

Do not explain from memory, file names, guesses, or previous summaries when source code is available.

A good tutorial document must connect:

```text
source code -> execution flow -> data shape -> beginner explanation
```

## Writing Style

Write for a beginner who can slowly read code but does not yet understand the system.

Use:

- real class/function/method names
- real variable names
- real field names
- real input examples
- real output examples
- complete object structures
- before/after data examples
- concrete call chains
- state changes
- event structures
- failure/fallback behavior

Avoid:

- vague architecture filler
- motivational fluff
- “后面详讲”
- “后续会说”
- “这里省略”
- “大致理解即可”
- “这一步先准备容器”
- “重点看每一步”
- placeholder pseudocode when real code exists
- unexplained technical terms

If a technical term appears, explain it immediately in plain language.

Example:

```text
DAG 是 Directed Acyclic Graph，中文叫“有向无环图”。

在代码里，它通常不是一个神秘对象，而是几类数据结构组合起来：
1. 节点表：保存所有节点
2. 入度表：记录每个节点还依赖几个前置节点
3. 邻接表：记录一个节点完成后会影响哪些后续节点
```

## General Workflow

Follow this order.

1. Identify the tutorial document the user wants edited.
2. Identify the source code being explained.
3. Read the relevant source files and methods completely.
4. Find the real execution chain:
   - who calls this method
   - what inputs it receives
   - what local variables it creates
   - what state it changes
   - what methods it calls next
   - what it returns
   - what errors or fallback paths it handles
5. Rewrite the documentation directly.
6. Remove old vague or repetitive text if it makes the section harder to understand.
7. Preserve the document's existing style and heading structure unless the user asks for a rewrite.
8. Fix broken heading numbering.
9. Run quality checks before finishing.

## Whole Document Structure

A tutorial document that explains one method, class, module, or runtime process should usually contain:

```text
1. 这部分解决什么问题
2. 它在整个系统里的位置
3. 谁会调用它
4. 输入和输出是什么
5. 核心源码
6. 代码逐段解释
7. 数据结构说明
8. 状态变化说明
9. 事件/输出说明
10. 成功路径例子
11. 失败/取消/降级路径例子
12. 常见误解
13. 一句话总结
```

Do not force every document into this exact structure, but complex code explanation documents should cover these points.

## Minimum Density Rule

Do not stop at a chapter skeleton.

If the user asks to "write", "rewrite", "补充", "详细解释", or "写进文档", the default expectation is a dense teaching chapter, not a light summary.

The following are too thin unless the user explicitly asks for a brief version:

```text
只有 1 段概述
只有一小段源码但没有逐段拆解
只列接口/对象名字，不解释字段和数据流
只写“成功 / 失败”标题，不给具体输入输出例子
只说“这个方法负责 xxx”，但不说参数、返回值、状态变化
```

By default, a finished chapter should feel like:

```text
读者不看源码，也能先建立稳定心智模型；
再回头对源码时，已经知道每一段在干什么。
```

If the code is important enough to have its own chapter, explain it to completion.

## Cross-Reference Rule

If the current chapter mentions a method, object, or branch that has its own detailed chapter elsewhere in the same tutorial folder, do not write vague phrases like:

```text
后面详讲
后续会展开
这一块先略过
这里先不展开
```

Instead, explicitly mark the follow-up chapter and make it clickable.

Good:

```md
这里先知道 `uploadFile(...)` 会调用 `DocumentParser.parseBytes(...)`。
`parseBytes` 的分流细节在 [03-DocumentParser-parseBytes.md](/abs/path/to/03-DocumentParser-parseBytes.md) 单独展开。
```

Also good:

```md
`writeDocument(req, true)` 内部会继续走文档库写入和 RAG ingest。
这条链路在 [08-DocumentLibraryService-writeDocument.md](/abs/path/to/08-DocumentLibraryService-writeDocument.md) 细讲。
```

Bad:

```text
这里后面详讲。
具体逻辑见后文。
这一块暂时不用展开。
```

The purpose of the current chapter is:

```text
先把当前链路讲清楚
再明确告诉读者：哪个细节会在后面哪一章展开
让读者能直接跳过去
```

## Cross-Reference When To Add

Add explicit cross-reference links in these situations:

```text
1. 当前方法直接调用了后面会单独讲的方法
2. 当前对象会在后面有独立对象章
3. 当前只做总览，但某个分支在后面有独立章节
4. 当前为了聚焦主线，只简要带过某个底层细节
5. 当前是多接口章，其中某个接口已有单独详细章节
```

Do not add links for every noun mechanically.

Add links when the forward reference truly helps the learner continue along the tutorial route.

## Cross-Reference Format

When writing links, prefer this pattern:

```md
这一步会调用 `DocumentParser.parseBytes(...)`，把原始字节统一转成 `ParseResult`。
分流规则和返回结构在 [03-DocumentParser-parseBytes.md](/absolute/path/to/file.md) 详细展开。
```

Or:

```md
这里先把 `ParseResult` 当成“解析后的中间态对象”理解即可。
字段逐个解释见 [06-文档库核心对象.md](/absolute/path/to/file.md)。
```

The link sentence should tell the reader:

```text
为什么要跳过去
跳过去能看到什么
当前章节先掌握到什么程度就够了
```

Do not just paste a bare filename link with no explanation.

## Cross-Reference Placement

Place forward links at the moment the reader naturally asks "这里接下来去哪看".

Typical placements:

```text
1. 在“它在整个系统里的位置”里：
   当前链路提到后续节点时，顺手给下一章链接

2. 在“代码逐段解释”里：
   某一步只做总览，但底层实现后面单讲时，紧跟解释后给链接

3. 在“对象说明”里：
   当前只把对象当作中间态介绍，字段细节在对象章展开时给链接

4. 在“成功路径例子”里：
   跑到某一步时提醒读者，这一步的细节在哪一章
```

Do not dump all forward links into one section at the top.

The best cross-reference is local and contextual:

```text
刚讲到这里
读者刚好会想知道更多
链接就放在这里
```

## Current Chapter First Rule

Cross-reference is a supplement, not an excuse to skip the current explanation.

In the current chapter, still explain:

```text
这一步在当前链路里起什么作用
输入输出大致是什么
为什么这里会调用下一个方法/对象
```

Then link to the later chapter for deeper detail.

Bad:

```text
这里会调用 parseBytes，具体见后文。
```

Good:

```text
这里会调用 `parseBytes(...)`，把上传的文件名、content-type 和原始字节统一转成 `ParseResult`。
也就是先把“上传文件”变成“系统可写入的解析结果”。
分流规则和 PDF/文本两条管线在 [03-DocumentParser-parseBytes.md](/absolute/path/to/file.md) 详细展开。
```

## Cross-Reference Coverage Check

When a tutorial folder already has a learning-route chapter or an ordered chapter list, use it to discover downstream detailed chapters.

Check:

```text
当前章里提到的方法
当前章里提到的对象
当前章里提到的分支
```

If one of them already has a dedicated later chapter, add a cross-reference link unless doing so would clearly be noisy or redundant.

Examples:

```text
uploadFile 里提到 parseBytes         -> 链到 03-DocumentParser-parseBytes.md
uploadFile 里提到 writeDocument     -> 链到 08-DocumentLibraryService-writeDocument.md
writeDocument 里提到 rag.ingest     -> 链到 10-RagService-ingest.md
queryWithHistory 里提到 searchMulti -> 链到 20-RagService-searchMulti.md
queryWithHistory 里提到 rerank      -> 链到 25-LLMReranker-rerank.md
```

If there is no dedicated later chapter, do not fabricate one.

## Chapter Type Detection

Before writing, classify the chapter into one of these types and follow the matching template.

### Type A: Single Method / Single Runtime Method

Examples:

```text
uploadFile
writeDocument
queryWithHistory
parsePDF
runStream
invoke
```

Use the full source-level teaching format.

### Type B: Multi-Method / Interface Group Chapter

Examples:

```text
upload 和 documents 接口
一组 CRUD 接口
几个相近 helper 方法
同一个 controller 下的多个入口
```

Do not write this as a loose overview.

For each method/interface in the group, explain:

```text
方法签名
输入是什么
返回什么
和其他接口的区别
典型使用场景
成功/失败例子
最容易混淆的点
```

Also add a comparison section at the end:

```text
这几个接口分别解决什么问题
它们的输入类型有什么区别
它们分别写文档库、写 RAG、还是只读
面试里最容易说混的是哪几对
```

### Type C: Object / Data Structure Chapter

Examples:

```text
Node
Tool
ToolCallResult
ParseResult
Document / DocumentVersion
QueryResult / ScoredChunk
```

Do not write object chapters as a glossary.

For each object, explain:

```text
这个对象代表什么
它和相邻对象的关系
完整字段表，不能省略执行相关字段
谁创建它
谁读取它
谁修改它
它在主链路什么时候出现
一个真实对象例子
最容易和哪个对象混淆
```

If multiple objects are grouped in one chapter, include:

```text
对象之间的关系图或文字链路
从上游对象如何转换到下游对象
一个 before/after 结构例子
```

### Type D: Parsing / Cleanup / Conversion Chapter

Examples:

```text
parseBytes
parsePDF
normalizeText
planGraph 里的清洗和解析
JSON 解析和格式降级
```

These chapters must show:

```text
原始输入长什么样
清洗前是什么
每条清洗规则是什么
清洗后是什么
解析成功后对象长什么样
如果格式 1 失败，怎么退到格式 2
全部失败后怎么降级
```

Do not summarize parsing logic with phrases like:

```text
“这里做格式处理”
“这里做兼容解析”
“这里按多种格式解析”
```

Show the actual shapes.

## Strong Output Requirements By Chapter Type

### Type A Minimum Requirements

A single-method chapter should usually include all of:

```text
1. 这部分解决什么问题
2. 它在整个系统里的位置
3. 谁会调用它
4. 方法签名先看懂
5. 方法源码，源码里加编号注释
6. 参数逐个解释
7. 返回值结构说完整
8. 代码逐段解释
9. 状态变化说明
10. 函数调用链
11. 完整成功例子
12. 失败 / 取消 / 降级例子
13. 常见误解
14. 一句话总结
```

Do not omit sections 6-12 just because the method is short.

Short method is not the same as simple chapter.

For Type A chapters, tutorial code blocks should default to line-by-line coverage.

That means:

```text
源码块里每一行有意义代码
都要在相邻注释里被解释到
```

If the method is long, you may split it into multiple code blocks, but each block still needs line-by-line coverage.

### Type B Minimum Requirements

A multi-interface chapter should usually include:

```text
1. 这组接口解决什么问题
2. 它们在整体链路中的分工
3. 每个接口单独展开
4. 每个接口的输入输出例子
5. 这几个接口的差异对比表
6. 常见混淆点
7. 一句话总结
```

Bad:

```text
upload 是直写 RAG。
writeDocument 是写文档库。
list/get 是读取。
delete 是删除。
```

Good:

```text
逐个解释每个接口的请求体、返回体、是否经过文档库、是否写 RAG、是否依赖文件解析、失败时返回什么。
最后再做横向对比。
```

If a Type B chapter includes code blocks for individual methods, those method code blocks should also follow line-by-line coverage.

### Type C Minimum Requirements

An object/data-structure chapter should usually include:

```text
1. 这组对象解决什么问题
2. 先记最重要的关系
3. 每个对象单独讲
4. 完整字段表
5. 字段之间的区别
6. 谁创建 / 谁消费 / 谁更新
7. 在主链路中的出现时机
8. 一个真实实例对象
9. 常见混淆点
10. 一句话总结
```

When showing a sample object, include all execution-relevant fields.

Bad:

```text
ParseResult = 解析结果
WriteRequest = 写请求
```

Good:

```json
ParseResult:
{
  "filename": "manual.pdf",
  "contentType": "application/pdf",
  "parser": "pdfplumber",
  "content": "第一章 ...",
  "pages": 12,
  "textChars": 18453,
  "needsOCR": false
}
```

If a Type C chapter shows constructor code, normalize code, conversion code, or object assembly code, those code blocks should also be commented line by line.

### Type D Minimum Requirements

A parsing/cleanup chapter should usually include:

```text
1. 输入原始长什么样
2. 为什么不能直接使用
3. 每一步清洗规则
4. 清洗前 / 清洗后例子
5. 解析成功后结构
6. 兼容格式 1 / 2 / 3
7. 降级逻辑
8. 常见误解
9. 一句话总结
```

For Type D chapters, line-by-line code comments are especially important, because beginners usually get lost in:

```text
if 分流条件
字符串清洗
异常分支
格式 1 / 2 / 3 兼容解析
fallback / downgrade
```

Do not summarize a parsing function with one big commented block.

Break it down so each branch and each returned structure is explained line by line.

## Anti-Skeleton Rule

Do not stop after these patterns:

```text
“主流程源码”
“三层提取器分别干什么”
“输入输出”
“一句话总结”
```

Those headings are only acceptable if followed by real teaching content:

```text
源码里的变量解释
真实输入例子
真实返回对象
异常分支
before/after 变化
调用链
为什么要这样设计
```

If after writing a section you could replace it with a bullet list without losing much meaning, it is probably still too thin.

## Code Explanation Chapter Rule

When the tutorial contains a section like:

```text
方法源码
代码解释
逐段解释
源码解析
核心源码
```

write it as a source-level teaching chapter.

The model pattern is:

```text
先讲职责边界
再展示完整源码
源码里用编号注释解释关键代码块
源码后再按步骤拆开讲
最后用完整例子跑一遍
```

### Required Parts

A code explanation chapter should include:

```text
1. Method responsibility
2. What this method does not handle
3. Full source code
4. Numbered inline comments inside the code block
5. Parameter and return value explanation
6. Step-by-step breakdown after the code block
7. State changes
8. Emitted events or produced data
9. Function call chain
10. Success example
11. Failure/cancel/fallback example
12. Common misunderstandings
13. One-sentence summary
```

## 1. Method Responsibility

Before showing code, explain what this method is responsible for.

Good:

```text
这个方法负责一个节点从“准备执行”到“执行结束”的完整状态机。
```

Also explain what it does not do.

Good:

```text
它不负责拓扑排序。
它不负责一层节点是否全部完成。
它不负责最终回答生成。
```

This prevents beginners from mixing responsibilities across methods.

## 2. Full Source With Numbered Inline Comments

When showing source code, do not paste raw code without explanation.

Add numbered comments for each meaningful block:

```java
// ① 从任务图里取当前节点对象。
//
// graph.getNodes() 是整张图的节点表。
// nodeId 是当前要执行的节点编号，比如 "n1"。
Node node = graph.getNodes().get(nodeId);

// ② 如果节点不存在，直接返回 null。
//
// 这里不会写 FAILED，因为连 Node 对象都没有，
// 没有地方保存 status/error。
if (node == null) return null;

// ③ 取执行体名称。
// 工具节点返回 toolName，子任务/子 Agent 节点返回 agentName。
String executor = node.executorName();
```

Each numbered comment should explain:

```text
这段代码从哪里取数据
取出来的值可能是什么
为什么要这么做
后面谁会用这个值
```

Do not write empty comments like:

```text
// 设置变量
// 执行逻辑
// 返回结果
```

## Line-By-Line Comment Rule

When the user wants detailed code explanation, every line inside a tutorial code block should be covered by comments.

This means:

```text
每一行有意义的代码
都要有对应解释
不能跳过中间变量
不能跳过条件判断
不能跳过 return
不能跳过异常分支
不能只给一整段写一句总评
```

Important:

```text
“每一行都要注释”
不是要求给每个花括号单独写废话
也不是要求写“定义变量”“调用方法”这种空注释
而是要求每一行有执行意义的代码，都能让新手知道：
这一行拿了什么值
为什么要这么写
后面谁会用
```

Good pattern:

```java
// ① 先从请求里取 content 字段。
// 这里取出来的还是原始字符串，可能是 null。
String content = req.get("content");

// ② 如果 content 为空，直接返回错误。
// 这里不继续往下走，因为 rag.ingest(...) 需要真实正文。
if (content == null || content.isEmpty()) return Map.of("error", "content is required");

// ③ 调用 RAG 写入入口。
// 返回值是一个二元组：chunk 数量 + 文档哈希。
Map.Entry<Integer, String> result = agent.getRagService().ingest(content);
```

Bad pattern:

```java
// 处理请求
String content = req.get("content");
if (content == null || content.isEmpty()) return Map.of("error", "content is required");
Map.Entry<Integer, String> result = agent.getRagService().ingest(content);
```

The bad version does not actually explain each line.

## Comment Granularity Rule

Use one of these two styles:

### Style A: One logical line, one comment group

Best for short methods.

```java
// ① 从 req 里取用户上传的正文。
String content = req.get("content");

// ② 正文为空就直接返回错误，不进入 ingest。
if (content == null || content.isEmpty()) return Map.of("error", "content is required");
```

### Style B: One statement, multiple comment lines above it

Best for dense or tricky lines.

```java
// ③ 这里真正进入 RAG 写入链路。
// agent.getRagService() 先拿到统一持有的 RagService。
// ingest(content) 会继续调用 split 和 index。
// 返回的 result.getKey() 是 chunk_count，result.getValue() 是 doc_hash。
Map.Entry<Integer, String> result = agent.getRagService().ingest(content);
```

Do not use block-level narration that leaves 4-8 lines uncommented underneath.

## Every Statement Must Be Covered

In tutorial code blocks, cover all statement lines such as:

```text
变量定义
方法调用
if / else if / else
for / while
try / catch
return
throw
对象构造
Map / List / JSON 拼装
状态赋值
事件推送
```

If a code block contains these lines:

```java
String title = ...
WriteRequest req = ...
DocumentLibraryService.Result res = ...
out.put(...)
return out;
```

then each one should have a nearby explanation, not just the first and last.

## Explain Small But Important Lines

Do not skip a line just because it looks simple.

These lines are often exactly where beginners get lost:

```text
三元表达式
Map.get(...)
Map.of(...)
new LinkedHashMap<>(...)
subList(...)
Collectors.joining(...)
Boolean.TRUE.equals(...)
getOrDefault(...)
异常类型判断
```

For example, do not write:

```text
这里构造请求对象。
```

Write:

```text
这里的 title 如果原始文件名为 null，就退化成 "{parser} upload"。
这样即使上传对象没有文件名，文档库里也不会出现空标题。
```

## 3. Explain Parameters and Return Values Separately

After the source code, explain the method signature.

Example:

```java
private String doExecuteNode(String nodeId, AtomicBoolean winnerFlag)
```

Use a table:

| 参数 | 含义 | 普通情况 | 特殊情况 |
|---|---|---|---|
| `nodeId` | 要执行的节点 ID | `"n1"` | 找不到节点时返回 `null` |
| `winnerFlag` | 是否已有竞速赢家 | `null` | 竞速节点传入共享 `AtomicBoolean` |

Return values must also be explained:

| 返回值 | 含义 |
|---|---|
| 非 `null` 字符串 | 执行成功，有结果 |
| `""` | 执行成功，但结果内容为空 |
| `null` | 没有成功结果 |

Always distinguish:

```text
return ""    -> 成功，只是内容为空
return null  -> 没有成功结果
```

## 4. Break Down Code Blocks One By One

After the full code block, create separate sections for important blocks.

Example headings:

```md
## 4. 第一步：从 graph 里取 Node
## 5. executorName：统一拿执行体名字
## 6. 推送开始事件
## 7. 状态改成 RUNNING
## 8. 校验执行体是否存在
## 9. 读取重试配置
## 10. 真正执行：invoke(node)
## 11. 失败结算
## 12. 成功结算
```

Each section should include:

```text
源码片段
输入例子
执行过程
执行后的状态/数据
为什么这样写
```

Bad:

```text
这里就是判断一下，然后执行。
```

Good:

```text
这里判断的是 tools.get(node.getToolName()) 是否为 null。

如果：

node.toolName = "fake_tool"

并且 tools 里没有这个 key，那么：

tools.get("fake_tool") == null

代码会把节点状态改成 FAILED，写入错误信息，并返回 null。
```

## 5. Explain Data Structures Completely

When a structure appears, do not leave it abstract.

Always explain:

```text
这个结构是什么
为什么需要它
代码里用什么字段/集合保存它
构造前是什么样
构造后是什么样
后面谁会使用它
```

Example:

```text
graph.nodes 是整张任务图的节点表。

结构类似：

{
  "n1": Node(...),
  "n2": Node(...),
  "n3": Node(...)
}

当 nodeId = "n1" 时：

graph.getNodes().get("n1")

会取出 n1 对应的 Node 对象。
```

If explaining parsed JSON or object construction, show the full structure:

```text
解析后对象：

{
  id: "n1",
  type: "tool",
  name: "查询天气",
  toolName: "get_weather",
  agentName: null,
  params: {
    city: "上海"
  },
  dependsOn: [],
  raceGroup: "",
  status: "PENDING",
  result: null,
  error: null
}
```

Do not omit fields that matter to execution.

## 6. Explain Parsing and Cleanup Logic

When explaining parsing, cleanup, normalization, or format conversion, include:

```text
清洗前
清洗规则
清洗后
解析格式 1
解析格式 2
解析格式 3
全部失败后的降级逻辑
```

Bad:

```java
try { /* 解析 */ return result; }
catch (Exception ignored) { /* fall through */ }
```

Good:

```text
第一次解析：按新 DAG 格式解析。

它期待的是 JSON 数组，每一项长这样：

{
  "id": "n1",
  "type": "tool",
  "tool": "search_web",
  "params": {
    "query": "北京天气"
  },
  "depends_on": [],
  "race_group": ""
}

如果这个格式解析失败，代码不会直接报错，而是进入第二种旧格式解析。
```

## 7. Explain State Changes Explicitly

If code changes status, show before and after.

Example:

```text
开始时：

PENDING

执行到这里后：

RUNNING

状态变化：

PENDING -> RUNNING
```

If source code has surprising behavior, say it directly.

Example:

```text
这里先把状态设成 CANCELLED。

但后面因为 lastErr != null，又会进入失败结算。

所以当前源码最终可能变成：

CANCELLED -> FAILED
```

Do not hide real source behavior because it looks strange.

## 8. Explain Events as Concrete Objects

If code emits events, show the event structure.

Example source:

```java
onEvent.accept(StreamEvent.toolCall(executor, node.getParams()));
```

Explain as:

```text
如果：

executor = "get_weather"
node.params = {"city":"上海"}

那么事件是：

{
  "type": "tool_call",
  "data": {
    "tool": "get_weather",
    "params": {"city":"上海"}
  }
}
```

Never only write:

```text
推送一个事件。
```

Say what event, what fields, what values, and who receives it.

## 9. Explain Function Calls as Chains

If a method calls another method, show the call chain.

Example:

```text
doExecuteNode("n1", null)
  -> invoke(node)
      -> tools.get("get_weather")
      -> params = {"city":"上海"}
      -> t.getExecute().apply(params)
      -> "上海：小雨 20°C"
```

The chain should show:

```text
当前方法
被调用方法
读取的数据
传入的参数
返回的结果
```

## 10. Explain Runtime and Concurrency Clearly

When explaining runtime code, always answer:

```text
是同步还是异步
是串行还是并行
并行单位是什么
```

Also explain:

```text
谁创建任务
谁真正执行任务
谁等待任务完成
谁保存结果
谁处理失败
谁发事件
谁生成最终输出
```

For concurrency terms, explain simply:

```text
线程池：一组提前准备好的工作线程，用来执行提交进来的任务。

Semaphore：许可证计数器，用来限制同一时间最多有多少个任务真的进入执行区。

CountDownLatch：倒计时等待器，用来让一个线程等多个任务都结束。

daemon 线程：守护线程，程序退出时不会因为它还活着而阻止 JVM 结束。
```

Do not leave runtime explanations at:

```text
这里用了线程池提高性能
这里做了并发控制
这里等待所有任务结束
```

Say exactly:

```text
哪个方法 submit 任务
提交进去的任务执行什么代码
是谁阻塞等待
等待结束后谁汇总结果
共享变量是什么
竞争节点是谁先写入 winner 标记
```

## Split Long Code Blocks Before Explaining

If a method is long enough that one huge code block would make the explanation unreadable, split it into multiple smaller blocks.

Recommended split styles:

```text
按执行阶段拆
按 if / else 分支拆
按 try / catch / fallback 拆
按“准备 -> 调用 -> 结算”拆
按“解析格式 1 -> 格式 2 -> 格式 3 -> 降级”拆
```

Good:

```text
先放完整方法源码
再把关键片段拆成 3-6 个小块分别逐行讲
```

Also good:

```text
如果完整源码过长，先给“关键主线源码”
再在后面按分支补源码片段
但每个片段都要逐行解释
```

Bad:

```text
贴一个 80 行大代码块
然后只在下面写 4 段大概说明
```

The point of splitting is not to reduce detail.

The point is:

```text
让逐行解释真的可读
让读者能按阶段消化
```

## Pseudo-Structure and JSON Explanation Rule

This skill does not only apply to Java/Python code blocks.

If the tutorial shows any of these:

```text
JSON 数组
Map 结构
请求体 / 返回体
对象样例
事件对象
中间态结构
伪结构体
表格里的字段定义
```

then explain them field by field.

At minimum, explain:

```text
字段名
字段类型或典型值
这个字段代表什么
这个字段是谁写进去的
后面谁会读取它
这个字段为什么重要
```

Bad:

```json
{
  "tool": "search_web",
  "params": {"query": "北京天气"},
  "depends_on": []
}
```

with only:

```text
这是一个工具节点。
```

Good:

```text
`tool` 表示真正要执行的工具名。
运行时会拿这个字段去 `tools` 映射里查执行体。

`params` 是传给工具的参数对象。
真正调用时会变成 `tool.getExecute().apply(params)` 的输入。

`depends_on` 表示这个节点必须等哪些上游节点完成后才能开始。
如果是空数组，说明它没有前置依赖，可以直接进入可执行集合。
```

## No Fake Line-By-Line Coverage

Do not pretend to do line-by-line explanation while actually skipping detail.

The following are still not acceptable:

```text
一个注释笼统盖住下面 5-8 行
只解释第一行和最后一行
把 3 个 return 合并成一句“最后返回结果”
把 try/catch 合并成一句“异常时降级”
把对象构造合并成一句“这里组装请求”
```

If there are multiple statements under one explanation, check whether a beginner could answer:

```text
这一行取了什么值
这一行为什么这样判断
这一行构造出来的对象长什么样
这一行 return 的到底是什么
```

If the answer is "不能", the explanation is still too coarse.

## Branch-by-Branch Parsing Explanation

For parsing, cleanup, and compatibility code, explain each branch in order.

Preferred pattern:

```text
先看原始输入
再看清洗规则
再看格式 1 尝试
失败后为什么进入格式 2
格式 2 失败后为什么进入格式 3
全部失败后为什么走规则降级
```

Do not compress this into:

```text
代码会尝试多种格式，失败后降级。
```

Instead, show a real example for each stage:

```text
清洗前是什么字符串
trim 后少了什么
去掉 function-calling wrapper 后剩什么
去掉 markdown fence 后剩什么
最后为什么已经能被 JSON 数组解析
```

If there are multiple accepted formats, explain each format with a full structure example instead of only listing field names.

## Quality Gate

Before finishing a tutorial document, run this mental checklist.

If any answer is "no", the document is probably still too thin.

```text
1. 我有没有真的读完相关源码，而不是只根据文件名写？
2. 这一章能不能回答“谁调它、传什么、返回什么、失败怎么办”？
3. 我有没有展示真实对象/JSON/Map/事件结构，而不是只写抽象名字？
4. 我有没有给至少一个完整成功例子？
5. 我有没有给至少一个失败、取消或降级例子？
6. 我有没有解释字段/状态/中间变量的变化？
7. 如果这是对象章，我有没有把字段说完整？
8. 如果这是解析章，我有没有写清洗前/后和格式降级？
9. 如果这是多接口章，我有没有做横向对比？
10. 如果把这章里的代码块去掉，正文还足够让新手理解吗？
11. 当前章提到的后续重点方法/对象，如果后面有独立章节，我有没有加可点击跳转？
12. 我的跳转是不是说明了“为什么跳过去、跳过去看什么”，而不是只丢一个文件名？
13. 代码块里的每一行有意义代码，我有没有逐行覆盖解释？
14. 我有没有跳过那些看起来短但实际容易让新手卡住的语句，比如三元表达式、Map.get、return、throw、out.put(...)？
15. 如果代码很长，我有没有拆成更适合教学的小代码块，而不是贴一大坨？
16. 如果出现 JSON / Map / 对象样例，我有没有逐字段解释，而不是只贴结构？
17. 我是不是用了“假逐行注释”，也就是一段空泛注释盖住很多行代码？
```

## Rewrite Priority

When the user says a chapter is:

```text
太粗糙
太简陋
没看懂
写细一点
补充完整
像小白一样讲
```

interpret that as:

```text
现有内容密度不够
需要重写，而不是在原文上补 2-3 句
默认应补到完整教学版标准
```

In these cases, prefer replacing weak sections entirely over preserving thin wording for continuity.

## 11. Explain Tool or Function Invocation

When explaining tool/function invocation, distinguish:

```text
工具定义在哪里
工具如何注册
工具名如何匹配
参数从哪里来
调用函数在哪里
返回值如何保存
返回值如何继续传给下一步
单工具调用和多工具调用有什么区别
```

Example chain:

```text
Node.params
  -> runtime reads params
  -> executor.invoke(toolName, params)
  -> tool.getExecute().apply(params)
  -> result string
  -> node.setResult(result)
  -> observation event
  -> final observations list
```

## 12. Use Complete Examples

Every important method should include at least one complete success example.

Example:

```text
nodeId = "n1"
type = TOOL
name = "查询上海天气"
toolName = "get_weather"
params = {"city":"上海"}
winnerFlag = null
```

Then walk through:

```text
1. node = graph.nodes["n1"]
2. executor = "get_weather"
3. 发送 node_start
4. 发送 tool_call
5. status: PENDING -> RUNNING
6. invoke(node)
7. result = "上海：小雨 20°C"
8. status: RUNNING -> DONE
9. 发送 node_done
10. 发送 observation
11. return "上海：小雨 20°C"
```

If the method has retry, fallback, race, cancel, or error behavior, include examples for those too.

## 13. Common Misunderstandings

End complex code explanation chapters with a "常见误解" section.

Example:

```md
## 常见误解

### 误解一：这个方法会不会写最终 results？

不会。

它只写：

graph.nodes[nodeId].status
graph.nodes[nodeId].result
graph.nodes[nodeId].error

最终 results 是外层方法写的。
```

Common misunderstanding sections are useful when:

```text
方法名容易误导
状态流转容易误解
return null / return "" 容易混
事件发送位置容易混
并发逻辑容易混
单工具调用和多工具调用容易混
```

## 14. One-Sentence Summary

End with a short summary.

Good:

```text
这个方法就是单个节点的执行状态机：

取 Node
  -> 推开始事件
  -> 标 RUNNING
  -> 校验执行体
  -> invoke
  -> 成功 DONE
  -> 失败 FAILED
  -> 返回结果或 null
```

The summary should compress the actual execution chain, not repeat abstract concepts.

## Editing Existing Tutorial Documents

When editing an existing tutorial document:

- modify the target document directly
- do not create a parallel replacement unless the user asks
- do not resurrect deleted documentation folders
- remove old vague explanations if they conflict with the new section
- keep heading numbering consistent
- preserve the document's current naming style
- prefer concrete short paragraphs over long abstract paragraphs

If the user says:

```text
这块太粗糙
小白看不懂
说详细点
你说了个屁
```

treat it as a request to rewrite the section, not just add one sentence.

## Quality Check

Before finishing, check for vague placeholder phrases:

```bash
rg -n "后面|后续|详讲|省略|大致理解|这一步先准备容器|重点看每一步" <doc-file>
```

Check markdown/git whitespace:

```bash
git diff --check -- <doc-file>
```

Check headings:

```bash
rg -n "^##|^###" <doc-file>
```

Fix problems before responding.

## Final Response

In the final response, keep it short.

Say:

```text
改了哪个文件
补了什么核心内容
做了什么检查
```

Do not re-explain the whole document unless the user asks.
