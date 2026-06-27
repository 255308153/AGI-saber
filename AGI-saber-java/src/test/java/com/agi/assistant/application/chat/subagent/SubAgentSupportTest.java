package com.agi.assistant.application.chat.subagent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * doc_agent 标题选择 / 上游内容拼接 / fence 剥离行为
 * （对应 Go application/chat/subagents_test.go）。
 */
class SubAgentSupportTest {

    @Test
    void documentTitlePrefersMarkdownH1() {
        String content = "## n2\n\n# PPO算法研究综述：对比TRPO的优势、实现特性及应用领域分析\n\n## 摘要\n...";
        String got = SubAgentSupport.documentTitle(content,
                "保存报告到本地文档库并写入 RAG", "写一份 PPO 报告");
        assertEquals("PPO算法研究综述：对比TRPO的优势、实现特性及应用领域分析", got);
    }

    @Test
    void documentContentPrefersWriterAgent() {
        Map<String, String> upstream = new LinkedHashMap<>();
        upstream.put("n3:review_agent", "Review: 需要补证据");
        upstream.put("n2:writer_agent", "# 真正的报告\n\n正文");
        SubAgentTask task = new SubAgentTask("n4", "保存", "生成报告", upstream);
        assertEquals("# 真正的报告\n\n正文", SubAgentSupport.documentContent(task));
    }

    @Test
    void documentTitlePrefersExplicitRequestedTitle() {
        String content = "# 模型生成的其他标题\n\n正文";
        String got = SubAgentSupport.documentTitle(content,
                "保存报告到本地文档库并写入 RAG",
                "生成一份标题为《子Agent联调测试》的简短报告");
        assertEquals("子Agent联调测试", got);
    }

    @Test
    void documentContentStripsMarkdownFence() {
        Map<String, String> upstream = new LinkedHashMap<>();
        upstream.put("n2:writer_agent", "```markdown\n# 子Agent联调测试\n\n正文\n```");
        SubAgentTask task = new SubAgentTask("n3", "保存", "生成报告", upstream);
        assertEquals("# 子Agent联调测试\n\n正文", SubAgentSupport.documentContent(task));
    }
}
