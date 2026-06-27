# 04-短期记忆对象-ConversationMessage

## 1. 一句话结论

`ConversationMessage` 是短期记忆里保存“一条聊天消息”的最小对象。

它只保存 3 个字段：

```text
role      = 这句话是谁说的
content   = 这句话的正文
timestamp = 这句话被写入短期记忆的时间
```

也就是说，短期记忆不是直接保存一个字符串列表，而是保存很多个 `ConversationMessage` 对象。

## 2. 在记忆系统里的位置

它的位置在短期记忆链路的最底层：

```text
用户提问
  ↓
ShortTermMemory.add("user", query)
  ↓
new ConversationMessage(role, content, timestamp)
  ↓
放进 ShortTermMemory.messages
  ↓
ChatHistoryAdapter 转成 LLM 能识别的 messages
```

所以 `ConversationMessage` 本身不是长期记忆，也不是图记忆，也不是 embedding。

它只是“最近几轮聊天记录”的一条消息。

## 3. 源码位置和核心对象

源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/model/ConversationMessage.java
```

真实源码结构：

```java
package com.agi.assistant.model; // 这个类属于 model 包，表示一个数据对象

public class ConversationMessage { // 定义短期记忆里的一条聊天消息
    private String role; // 消息角色，例如 "user" 或 "assistant"
    private String content; // 消息正文，例如用户问的问题，或助手的回答
    private String timestamp; // 写入短期记忆时的时间字符串，例如 "21:15:08"

    public ConversationMessage() {} // 无参构造方法，给框架或反序列化使用

    public ConversationMessage(String role, String content, String timestamp) { // 创建消息时一次性传入三个字段
        this.role = role; // 把传进来的角色保存到当前对象
        this.content = content; // 把传进来的正文保存到当前对象
        this.timestamp = timestamp; // 把传进来的时间保存到当前对象
    }

    public String getRole() { return role; } // 读取 role，后面 ChatHistoryAdapter 会用它判断是否能进 LLM messages
    public void setRole(String role) { this.role = role; } // 修改 role
    public String getContent() { return content; } // 读取 content，后面会作为 LLM message 的 content
    public void setContent(String content) { this.content = content; } // 修改 content
    public String getTimestamp() { return timestamp; } // 读取 timestamp，当前代码没有把它传给 LLM
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; } // 修改 timestamp
}
```

这个类里没有业务逻辑，只有字段、构造方法、getter、setter。

短期记忆在本系统里有 4 种存在形式，`ConversationMessage` 是第一种：

```text
1. ConversationMessage 对象形式：
   ConversationMessage{role, content, timestamp}

2. ShortTermMemory 列表形式：
   List<ConversationMessage> messages

3. histMsgs 上下文形式：
   List<Map<String,String>>，只保留 role/content

4. chat_history 持久化形式：
   数据库里保存 role/content/created_at，重启时恢复到 STM
```

这一篇只讲第 1 种：对象形式。

## 4. 核心流程图

```mermaid
flowchart TD
    A["用户输入 query"] --> B["ShortTermMemory.add(\"user\", query)"]
    B --> C["创建 ConversationMessage"]
    C --> D["role = user"]
    C --> E["content = query"]
    C --> F["timestamp = 当前 HH:mm:ss"]
    D --> G["放入 messages 列表"]
    E --> G
    F --> G
```

## 5. 源码讲解

### 5.1 先说这段代码是干什么的

`ConversationMessage` 做的事情很简单：

```text
把“一句话”包装成一个标准消息对象。
```

用户说一句话，不能只保存一句字符串。系统还要知道：

```text
这句话是谁说的？
这句话内容是什么？
这句话是什么时候写进短期记忆的？
```

所以它需要 3 个字段。

### 5.2 生活类比

可以把 `ConversationMessage` 想成聊天记录本里的一行表格：

```text
说话人       内容                         记录时间
user        我在学短期记忆                21:15:08
assistant   短期记忆保存最近几轮对话       21:15:11
```

每一行就是一个 `ConversationMessage`。

### 5.3 对应到代码：对象里存什么

```java
private String role;
private String content;
private String timestamp;
```

逐行解释：

```text
第 1 行：role 保存“谁说的”，例如 user 或 assistant。
第 2 行：content 保存“说了什么”，例如“短期记忆是什么”。
第 3 行：timestamp 保存“什么时候写入短期记忆”，例如 21:15:08。
```

对应到真实对象就是：

```text
ConversationMessage {
  role = "user",
  content = "短期记忆是什么",
  timestamp = "21:15:08"
}
```

技术点最后再说：

```text
private 表示字段不能被外部直接访问。
String 表示这三个字段都是字符串。
```

### 5.4 对应到代码：怎么创建一条消息

```java
public ConversationMessage(String role, String content, String timestamp) {
    this.role = role;
    this.content = content;
    this.timestamp = timestamp;
}
```

先说目的：

```text
外部传进来 role、content、timestamp，
这个构造方法把它们装进当前这条消息对象里。
```

生活类比：

```text
你拿到一张空表格。
别人告诉你：
说话人 = user
内容 = 短期记忆是什么
时间 = 21:15:08

你把这三个值填到表格对应的格子里。
```

逐行解释：

```text
第 1 行：定义一个构造方法，创建对象时必须传入 role、content、timestamp。
第 2 行：把外面传进来的 role，填到当前对象自己的 role 字段。
第 3 行：把外面传进来的 content，填到当前对象自己的 content 字段。
第 4 行：把外面传进来的 timestamp，填到当前对象自己的 timestamp 字段。
```

这里最容易卡住的是 `this.role = role`。

它不是重复写两遍。

```text
this.role  = 当前对象里面的 role 格子
role       = 外面传进来的 role 参数
```

所以：

```text
this.role = role
= 把外面传进来的值，放进当前对象自己的字段里
```

### 5.5 真实创建发生在哪里

```java
messages.add(new ConversationMessage(role, content,
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
```

先说目的：

```text
ShortTermMemory.add 收到一条消息后，
会创建一个 ConversationMessage，
然后放进短期记忆列表 messages。
```

为了更好理解，可以把上面一行拆成三步：

```java
String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")); // 生成当前时间，例如 "21:15:08"
ConversationMessage message = new ConversationMessage(role, content, now); // 把角色、正文、时间包成一个消息对象
messages.add(message); // 把这条消息放进短期记忆列表
```

逐行解释：

```text
第 1 行：先得到当前时间，格式是“小时:分钟:秒”。
第 2 行：用 role、content、now 创建一条聊天消息。
第 3 行：把这条消息放进 messages 这个短期记忆列表。
```

最后讲技术点：

```text
new ConversationMessage(...) 表示创建一个新对象。
messages.add(...) 表示把对象追加到列表末尾。
LocalTime.now() 表示拿当前时间。
DateTimeFormatter.ofPattern("HH:mm:ss") 表示把时间格式化成 21:15:08 这种字符串。
```

## 6. 真实例子：在流程中怎么运行

假设用户输入：

```text
我正在学习短期记忆，帮我用例子解释
```

`UnifiedAgentService.processInternal` 会先执行：

```java
stm.add("user", query);
```

进入 `ShortTermMemory.add` 后，会创建对象：

```text
ConversationMessage {
  role = "user",
  content = "我正在学习短期记忆，帮我用例子解释",
  timestamp = "21:15:08"
}
```

等助手回答完以后，又会执行：

```java
stm.add("assistant", resp.getAnswer());
```

这时会再创建一条：

```text
ConversationMessage {
  role = "assistant",
  content = "短期记忆就是保存最近几轮 user 和 assistant 的对话……",
  timestamp = "21:15:11"
}
```

所以一轮完整对话会产生两条 `ConversationMessage`：

```text
第 1 条：user      用户问题
第 2 条：assistant 助手回答
```

## 7. 容易混淆的点

`ConversationMessage` 不是长期记忆。

长期记忆保存的是“可以以后长期复用的事实”，例如“用户喜欢 Java 代码逐行讲解”。

`ConversationMessage` 保存的是最近对话原文，例如“刚才用户问了什么、助手回答了什么”。

`timestamp` 当前不会进入 LLM。

`ChatHistoryAdapter` 转换历史消息时只取：

```java
Map.of("role", m.getRole(), "content", m.getContent())
```

所以 LLM 看到的是 `role/content`，不是完整的 `ConversationMessage` 对象。

## 8. 面试怎么说

可以这样说：

```text
短期记忆的最小单位是 ConversationMessage，它保存 role、content、timestamp 三个字段。
用户消息和助手消息都会先包装成 ConversationMessage，再放入 ShortTermMemory 的 messages 列表。
后续 ChatHistoryAdapter 会把这些对象转换成 LLM 接口需要的 role/content message 列表。
```
