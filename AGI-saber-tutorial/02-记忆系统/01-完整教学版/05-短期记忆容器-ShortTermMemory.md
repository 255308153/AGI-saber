# 05-短期记忆容器-ShortTermMemory

## 1. 一句话结论

`ShortTermMemory` 是短期记忆容器，里面维护一个 `messages` 列表，用来保存最近几轮 `ConversationMessage`。

它负责三件事：

```text
1. 写入一条消息
2. 控制最多保留几轮对话
3. 对外返回一份历史消息副本
```

## 2. 在记忆系统里的位置

它处在 `UnifiedAgentService` 和 `ConversationMessage` 中间：

```text
UnifiedAgentService
  ↓ 调用 stm.add(...)
ShortTermMemory
  ↓ 创建并保存
ConversationMessage
```

`UnifiedAgentService` 不直接维护一个列表，而是把短期记忆相关操作交给 `ShortTermMemory`。

## 3. 源码位置和核心对象

源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/service/memory/ShortTermMemory.java
```

真实核心字段：

```java
@Component // 交给 Spring 管理，别的类可以通过依赖注入拿到同一个 ShortTermMemory
public class ShortTermMemory {

    private final List<ConversationMessage> messages = Collections.synchronizedList(new ArrayList<>()); // 保存短期消息的列表，并用 synchronizedList 包一层，降低并发访问风险
    private int maxTurns = 5; // 默认最多保留 5 轮对话，一轮通常等于 user + assistant 两条消息
```

`@Component` 的意思是：这个类会成为 Spring 容器里的一个组件。

所以在 `UnifiedAgentService` 构造方法里传入的 `ShortTermMemory stm`，就是 Spring 管理的这个对象。

短期记忆在这一层的存在形式是：

```text
List<ConversationMessage> messages
```

也就是：

```text
[
  ConversationMessage{role="user", content="...", timestamp="..."},
  ConversationMessage{role="assistant", content="...", timestamp="..."}
]
```

它和 `histMsgs` 不同。`messages` 是系统内部对象列表，`histMsgs` 是准备传给 LLM 的 Map 列表。

## 4. 核心流程图

```mermaid
flowchart TD
    A["UnifiedAgentService 收到 query"] --> B["stm.add(\"user\", query)"]
    B --> C["ShortTermMemory.messages 添加 user 消息"]
    C --> D["ChatHistoryAdapter.buildHistory(stm, query)"]
    D --> E["生成 histMsgs"]
    E --> F["传给 LLM"]
    F --> G["得到回答 answer"]
    G --> H["stm.add(\"assistant\", answer)"]
    H --> I["ShortTermMemory.messages 添加 assistant 消息"]
```

## 5. 源码讲解

### 5.1 先说这个类是干什么的

`ShortTermMemory` 可以先不要理解成“组件”“线程安全容器”。

先把它理解成：

```text
当前程序运行期间的一本聊天记录本。
```

用户说一句，写进去。

AI 回答一句，也写进去。

下一轮用户再问时，系统会翻这本记录本，把最近几轮对话拿给大模型看。

### 5.2 生活类比

`ShortTermMemory` 像一本只能保存最近几页的笔记本：

```text
第 1 页：user      我在学短期记忆
第 2 页：assistant 短期记忆保存最近几轮对话
第 3 页：user      histMsgs 是什么
第 4 页：assistant histMsgs 是给 LLM 的消息列表
```

如果这本本子最多只能放 10 页，新写第 11 页时，就撕掉最旧的第 1 页。

这就是 `maxTurns` 裁剪。

### 5.3 对应到代码：Spring 怎么拿到这本记录本

```java
@Component // 声明这是 Spring 组件，UnifiedAgentService 可以注入它
public class ShortTermMemory { // 短期记忆容器类
```

先说目的：

```text
让 Spring 启动时自动创建一个 ShortTermMemory 对象。
以后 UnifiedAgentService 需要短期记忆时，直接从 Spring 拿这个对象。
```

生活类比：

```text
Spring 像一个对象仓库。
@Component 就像贴了一个“请帮我放进仓库”的标签。
程序启动时，Spring 看到这个标签，就创建 ShortTermMemory，并保存起来。
```

技术点：

```text
@Component 是 Spring 注解。
它让 ShortTermMemory 成为 Spring Bean。
UnifiedAgentService 构造方法里需要 ShortTermMemory 时，Spring 会自动注入。
```

### 5.4 对应到代码：聊天记录本存在哪里

```java
private final List<ConversationMessage> messages =
        Collections.synchronizedList(new ArrayList<>());
private int maxTurns = 5;
```

先说目的：

```text
messages 就是那本聊天记录本。
maxTurns 控制这本记录本最多保留几轮对话。
```

生活类比：

```text
ArrayList 像一个按页码排列的笔记本。
第 0 页是最老的消息。
最后一页是最新的消息。
messages.add(...) 就是在最后加一页。
messages.remove(0) 就是撕掉最旧的一页。
```

逐行解释：

```text
第 1 行：定义 messages，用来保存很多条 ConversationMessage。
第 2 行：真正的底层列表是 new ArrayList<>()，也就是按顺序存放消息。
第 2 行：Collections.synchronizedList(...) 给列表加一层同步保护，降低多线程同时访问时写乱的风险。
第 3 行：maxTurns 默认是 5，表示最多保留 5 轮对话。
```

最后讲技术点：

```text
List<ConversationMessage> 表示列表里只能放 ConversationMessage 对象。
final 表示 messages 这个变量不能再指向另一本“新笔记本”，但笔记本里面仍然可以继续 add/remove。
ArrayList 底层按顺序存数据，适合保存聊天消息这种有先后顺序的数据。
```

### 5.5 对应到代码：怎么写入一条消息

```java
public void add(String role, String content) { // 写入一条短期消息，role 表示谁说的，content 表示说了什么
    messages.add(new ConversationMessage(role, content, // 新建 ConversationMessage 并追加到列表末尾
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))); // timestamp 使用当前时间，格式是小时:分钟:秒
    int max = maxTurns * 2; // 一轮对话有 user 和 assistant 两条，所以最大消息数 = 最大轮数 * 2
    while (messages.size() > max) { // 如果消息数量超过上限，就持续删除最旧消息
        messages.remove(0); // 删除列表第 0 个元素，也就是最早写入的那条消息
    }
}
```

先说目的：

```text
add 方法负责把一条新消息写进短期记忆。
写完以后，它还会检查消息是不是太多了。
如果太多，就删掉最旧的。
```

按流程翻译：

```text
第 1 步：收到 role 和 content。
第 2 步：加上当前时间，组成 ConversationMessage。
第 3 步：把这条消息追加到 messages 末尾。
第 4 步：计算最多允许保存多少条消息。
第 5 步：如果超出上限，就从第 0 条开始删除旧消息。
```

真实例子：

```text
调用：
stm.add("user", "短期记忆是什么")

进入方法后：
role = "user"
content = "短期记忆是什么"

创建出来：
ConversationMessage{role="user", content="短期记忆是什么", timestamp="21:20:01"}

然后放进 messages。
```

### 5.6 对应到代码：怎么读取短期记忆

```java
public List<ConversationMessage> getMessages() { // 对外读取当前短期记忆
    return new ArrayList<>(messages); // 返回一个新列表，避免外部代码直接改内部 messages
}

public int size() { return messages.size(); } // 返回当前短期记忆里有多少条消息
}
```

先说目的：

```text
getMessages 是把当前聊天记录拿出去给别人看。
size 是问这本记录本里现在有多少条消息。
```

生活类比：

```text
别人想看你的笔记本，你不把原本直接交出去。
你复印一份给他。
他在复印件上乱画，不会影响你的原本。
```

对应到代码：

```java
return new ArrayList<>(messages);
```

这句表示：

```text
用当前 messages 的内容，复制出一个新的 ArrayList 返回。
```

技术点：

```text
这里复制的是列表容器，目的是避免外部代码直接 clear/add/remove 内部 messages。
但列表里的 ConversationMessage 对象本身不是深拷贝。
对当前学习链路来说，重点先记住：它保护的是 messages 这个列表结构。
```

## 6. 真实例子：在流程中怎么运行

假设当前配置：

```text
shortTermMaxTurns = 5
```

启动时 `UnifiedAgentService.init()` 会执行：

```java
stm.setMaxTurns(cfg.getMemory().getShortTermMaxTurns());
```

也就是把配置里的 `5` 设置到 `ShortTermMemory.maxTurns`。

用户第一轮说：

```text
我正在学习短期记忆
```

`messages` 变成：

```text
[
  ConversationMessage{role="user", content="我正在学习短期记忆", timestamp="21:20:01"}
]
```

助手回答后：

```text
[
  ConversationMessage{role="user", content="我正在学习短期记忆", timestamp="21:20:01"},
  ConversationMessage{role="assistant", content="短期记忆保存最近几轮对话……", timestamp="21:20:04"}
]
```

这就是一轮对话保存成两条消息。

## 7. 容易混淆的点

`maxTurns` 不是最大消息条数。

源码里真正比较的是：

```java
int max = maxTurns * 2;
```

如果 `maxTurns = 5`，最多保留：

```text
5 轮 * 每轮 2 条 = 10 条消息
```

`messages` 是内存里的列表。

它和数据库聊天记录不是同一个东西。

当前代码里会同时做两件事：

```java
stm.add("user", query);              // 写入内存里的短期记忆
infra.saveChatHistory("user", query); // 写入数据库聊天历史
```

短期记忆用于本轮 LLM 上下文，数据库聊天历史用于持久化和重启恢复。

## 8. 面试怎么说

可以这样说：

```text
ShortTermMemory 是一个 Spring 组件，内部用 synchronizedList 保存 ConversationMessage。
每次用户输入和助手回答都会通过 add 写入短期记忆。
它用 maxTurns * 2 控制最大消息数，超过上限就从列表头部删除最旧消息。
读取时通过 getMessages 返回副本，避免外部直接修改内部列表。
```
