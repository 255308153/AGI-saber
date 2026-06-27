# 11-轻量级偏好规则-extractAndSave

## 1. 一句话结论

`extractAndSave` 是轻量级偏好规则抽取：不调用大模型，只用字符串规则判断用户输入里有没有“我喜欢 / 我爱 / 我叫”。

它的优点是快、确定、同步执行。

它的缺点是粗糙，只能识别少数固定表达。

## 2. 在记忆系统里的位置

它在主流程中同步执行：

```java
String[] extracted = pref.extractAndSave(query);
```

位置在：

```text
用户 query 写入短期记忆之后
构造 memPrefix 和 histMsgs 之前
```

所以它抽到的偏好可以较快进入当前流程。

## 3. 源码位置和核心对象

源码位置：

```text
AGI-saber-java/src/main/java/com/agi/assistant/service/memory/PreferenceMemory.java
```

核心方法：

```java
public String[] extractAndSave(String msg)
```

返回值：

```text
抽到偏好：new String[]{key, value}
没抽到：null
```

存在形式变化：

```text
用户 query 字符串
  → 字符串 split
  → key/value
  → PreferenceMemory.data
  → resp.extractedInfo
```

## 4. 核心流程图

```mermaid
flowchart TD
    A["用户 msg"] --> B{"包含 我喜欢 ?"}
    B -- "是" --> C["split(\"喜欢\", 2)"]
    C --> D["保存 喜好=value"]
    B -- "否" --> E{"包含 我爱 ?"}
    E -- "是" --> F["split(\"爱\", 2)"]
    F --> D
    E -- "否" --> G{"包含 我叫 ?"}
    G -- "是" --> H["split(\"叫\", 2)"]
    H --> I["保存 姓名=value"]
    G -- "否" --> J["返回 null"]
```

## 5. 源码讲解

### 5.1 先说轻量级规则是干什么的

`extractAndSave` 可以先理解成：

```text
用几个固定关键词，从用户输入里快速抓偏好。
```

它不调用大模型。

它只是看用户这句话里有没有：

```text
我喜欢
我爱
我叫
```

命中了，就用字符串切分，把后面的内容保存成偏好。

### 5.2 生活类比

你可以把它想成一个很简单的前台登记员。

用户说：

```text
我喜欢 Java 逐行解释
```

登记员听到“我喜欢”，就登记：

```text
喜好 = Java 逐行解释
```

用户说：

```text
我叫小李
```

登记员听到“我叫”，就登记：

```text
姓名 = 小李
```

这个登记员反应快，但理解能力弱。

### 5.3 对应到代码：识别“我喜欢”

```java
if (msg.contains("我喜欢")) { // 只要用户输入包含“我喜欢”三个字，就进入这个规则
    String[] parts = msg.split("喜欢", 2); // 按第一次出现的“喜欢”切成两段
    if (parts.length == 2 && !parts[1].trim().isEmpty()) { // 右边有内容才认为抽到了偏好
        String key = "喜好", value = parts[1].trim(); // key 固定为“喜好”，value 是“喜欢”后面的文本
        data.put(key, value); // 写入偏好 Map
        return new String[]{key, value}; // 返回给主流程，用于提示“已记住”
    }
}
```

先说目的：

```text
如果用户说了“我喜欢 XXX”，就把 XXX 保存为“喜好”。
```

真实例子：

```text
msg = "我喜欢用 Java 代码逐行解释"
```

执行：

```java
msg.contains("我喜欢")
```

结果是：

```text
true
```

然后执行：

```java
String[] parts = msg.split("喜欢", 2);
```

切分结果：

```text
parts[0] = "我"
parts[1] = "用 Java 代码逐行解释"
```

逐行解释：

```text
第 1 行：判断 msg 里是否包含“我喜欢”。
第 2 行：按“喜欢”切开，只切一次。
第 3 行：确认切成了两段，并且右边内容不是空。
第 4 行：key 固定为“喜好”，value 取“喜欢”右边的内容。
第 5 行：写入偏好 Map。
第 6 行：把抽取结果返回给主流程。
```

技术点：

```text
split("喜欢", 2) 里的 2 表示最多切成两段。
trim() 表示去掉前后空格。
```

### 5.4 对应到代码：识别“我爱”

```java
if (msg.contains("我爱")) { // 识别另一种喜好表达
    String[] parts = msg.split("爱", 2); // 按第一次出现的“爱”切分
    if (parts.length == 2 && !parts[1].trim().isEmpty()) { // 右侧内容不能为空
        String key = "喜好", value = parts[1].trim(); // 同样写入“喜好”
        data.put(key, value); // 保存或覆盖旧喜好
        return new String[]{key, value}; // 返回抽取结果
    }
}
```

先说目的：

```text
“我爱 XXX”和“我喜欢 XXX”都被当成喜好。
```

真实例子：

```text
msg = "我爱写 Java"
```

切分后：

```text
parts[0] = "我"
parts[1] = "写 Java"
```

保存成：

```text
喜好 = 写 Java
```

这也是轻量级规则粗糙的地方：

```text
它不知道“爱写 Java”更自然。
它只机械地把“爱”后面的内容当 value。
```

### 5.5 对应到代码：识别“我叫”

```java
if (msg.contains("我叫")) { // 识别姓名表达
    String[] parts = msg.split("叫", 2); // 按第一次出现的“叫”切分
    if (parts.length == 2 && !parts[1].trim().isEmpty()) { // 名字部分不能为空
        String key = "姓名", value = parts[1].trim(); // key 固定为“姓名”
        data.put(key, value); // 保存姓名
        return new String[]{key, value}; // 返回抽取结果
    }
}
return null; // 三类规则都没命中，就返回 null
```

先说目的：

```text
如果用户说“我叫 XXX”，就把 XXX 保存为“姓名”。
```

真实例子：

```text
msg = "我叫小李"
```

切分后：

```text
parts[0] = "我"
parts[1] = "小李"
```

保存成：

```text
姓名 = 小李
```

逐行解释：

```text
第 1 行：判断是否包含“我叫”。
第 2 行：按“叫”切成两段。
第 3 行：确认名字部分不是空。
第 4 行：key 固定为“姓名”，value 是“叫”后面的内容。
第 5 行：保存到 data。
第 6 行：返回抽取结果。
第 8 行：如果三类规则都没命中，返回 null。
```

### 5.6 轻量级到底什么时候用

当前 Java 版不是“有时用轻量级，有时用 LLM”。

主流程里是固定执行：

```java
runAsyncPreferenceExtraction(query);
String[] extracted = pref.extractAndSave(query);
```

也就是：

```text
LLM 异步偏好抽取：每轮启动，但在后台跑。
轻量级规则抽取：每轮同步执行，马上返回结果。
```

所以不是随机，也不是按运行时间二选一。

区别是：

```text
轻量级规则：
  不调用模型，当前线程马上跑完。
  只能识别固定关键词。

LLM 异步抽取：
  调用模型，理解能力更强。
  在后台线程执行，不阻塞本轮回答。
```

## 6. 真实例子：在流程中怎么运行

输入：

```text
我喜欢用 Java 代码逐行解释
```

执行：

```java
msg.contains("我喜欢") == true
```

切分：

```text
"我喜欢用 Java 代码逐行解释".split("喜欢", 2)
  → ["我", "用 Java 代码逐行解释"]
```

保存：

```text
key = "喜好"
value = "用 Java 代码逐行解释"
```

`PreferenceMemory.data` 变成：

```text
{
  "喜好": "用 Java 代码逐行解释"
}
```

主流程里：

```java
if (extracted != null) {
    resp.setExtractedInfo("已记住：" + extracted[0] + " = " + extracted[1]);
}
```

所以响应对象会带：

```text
已记住：喜好 = 用 Java 代码逐行解释
```

## 7. 容易混淆的点

轻量级规则不是根据运行时间随机决定的。

当前代码是固定执行：

```text
每轮 query 都会同步跑 extractAndSave
每轮 query 也会启动异步 runAsyncPreferenceExtraction
```

两者不是二选一。

如果两者抽到同一个 key，当前代码没有冲突仲裁机制，后写入的值会覆盖旧值。

还有一个细节：规则抽取比较粗。

例如：

```text
我叫小李，我喜欢 Java
```

规则里的“我叫”会把 `叫` 后面的整段都作为姓名：

```text
姓名 = 小李，我喜欢 Java
```

这就是为什么还需要 LLM 异步抽取做更精细的结构化识别。

## 8. 面试怎么说

可以这样说：

```text
轻量级偏好规则是同步执行的启发式抽取，目前只识别“我喜欢”“我爱”“我叫”三类表达。
它不调用模型，所以速度快、结果确定，但抽取能力有限。
系统同时还会启动异步 LLM 偏好抽取，用来补足规则抽取不够准确、不够全面的问题。
```
