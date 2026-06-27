package com.agi.assistant.application.chat.subagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * SubAgent 共用文本/标题/内容工具（对应 Go application/chat/subagents.go 的私有辅助函数）。
 *
 * <p>故意做成无状态 static —— 都是文本启发式，本身不需要依赖注入。</p>
 */
public final class SubAgentSupport {

    private SubAgentSupport() {}

    /** 把上游节点结果拼成 markdown，按 key 排序后用 "## key\n\n内容\n\n" 衔接。 */
    public static String upstreamText(SubAgentTask task) {
        if (task.upstream == null || task.upstream.isEmpty()) {
            return task.query;
        }
        StringBuilder b = new StringBuilder();
        for (String id : sortedKeys(task.upstream)) {
            String result = task.upstream.get(id);
            b.append("## ").append(id).append("\n\n").append(result).append("\n\n");
        }
        return b.toString().strip();
    }

    /** 选要写入文档库的正文：优先 writer_agent 的产出；否则取第一个非空上游；最后 fallback 到原始 query。 */
    public static String documentContent(SubAgentTask task) {
        String writer = upstreamByAgent(task, "writer_agent");
        if (writer != null && !writer.isBlank()) return stripMarkdownFence(writer);
        if (task.upstream != null) {
            for (String id : sortedKeys(task.upstream)) {
                String v = task.upstream.get(id);
                if (v != null && !v.isBlank()) return stripMarkdownFence(v);
            }
        }
        return task.query == null ? "" : task.query.strip();
    }

    /** 拿某个 sub-agent 的输出（按 key 包含 agentName 配对）。 */
    public static String upstreamByAgent(SubAgentTask task, String agentName) {
        if (task.upstream == null || agentName == null) return "";
        for (String id : sortedKeys(task.upstream)) {
            if (id.contains(agentName)) {
                String v = task.upstream.get(id);
                return v == null ? "" : v;
            }
        }
        return "";
    }

    /** 选最终文档标题：用户明确指定 → markdown H1 → 安全回退。 */
    public static String documentTitle(String content, String goal, String query) {
        String explicit = explicitRequestedTitle(query);
        if (!explicit.isEmpty()) return firstRunes(explicit, 80);
        explicit = explicitRequestedTitle(goal);
        if (!explicit.isEmpty()) return firstRunes(explicit, 80);
        String md = markdownTitle(content);
        if (!md.isEmpty()) return firstRunes(md, 80);
        return safeTitle("", fallbackTitleInput(goal, query));
    }

    /** 用户用 "标题为《...》" / "题为\"...\"" 这种格式显式给了标题时取出来。 */
    static String explicitRequestedTitle(String s) {
        if (s == null) return "";
        s = s.strip();
        for (String marker : new String[]{"标题为《", "标题是《", "题为《"}) {
            int start = s.indexOf(marker);
            if (start < 0) continue;
            String rest = s.substring(start + marker.length());
            int end = rest.indexOf("》");
            if (end > 0) return rest.substring(0, end).strip();
        }
        for (String marker : new String[]{"标题为\"", "标题是\"", "题为\""}) {
            int start = s.indexOf(marker);
            if (start < 0) continue;
            String rest = s.substring(start + marker.length());
            int end = rest.indexOf("\"");
            if (end > 0) return rest.substring(0, end).strip();
        }
        return "";
    }

    /** 找 markdown 里第一个非通用的 H1；找不到 H1 时退回找第一个非通用 H2-H6。 */
    static String markdownTitle(String content) {
        if (content == null) return "";
        content = stripMarkdownFence(content);
        String fallback = "";
        boolean inFence = false;
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("```")) { inFence = !inFence; continue; }
            if (inFence) continue;
            int[] heading = markdownHeading(line);
            if (heading == null) continue;
            int level = heading[0];
            String title = line.substring(level).strip();
            title = stripChars(title, "# \t").strip();
            title = stripChars(title, "*_`").strip();
            if (title.isEmpty() || isGenericDocHeading(title)) continue;
            if (level == 1) return title;
            if (fallback.isEmpty()) fallback = title;
        }
        return fallback;
    }

    /** 返回 [level] 当且仅当行是合法的 markdown 标题；否则 null。 */
    static int[] markdownHeading(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        if (level == 0 || level > 6 || level >= line.length() || line.charAt(level) != ' ') return null;
        return new int[]{level};
    }

    /** 去掉首末的 markdown ``` 围栏。 */
    public static String stripMarkdownFence(String s) {
        if (s == null) return "";
        String trimmed = s.strip();
        String[] lines = trimmed.split("\n", -1);
        if (lines.length < 2) return trimmed;
        if (!lines[0].strip().startsWith("```")) return trimmed;
        if (!lines[lines.length - 1].strip().startsWith("```")) return trimmed;
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < lines.length - 1; i++) {
            if (i > 1) b.append('\n');
            b.append(lines[i]);
        }
        return b.toString().strip();
    }

    /** 通用 / 无信息量的小标题（"摘要" / "分析" / "Findings" / "n2" 等）→ 不能拿来当文档标题。 */
    static boolean isGenericDocHeading(String title) {
        String t = title == null ? "" : title.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return true;
        if (t.codePointCount(0, t.length()) <= 3 && t.startsWith("n")) return true;
        switch (t) {
            case "摘要": case "分析": case "建议": case "下一步": case "结论":
            case "review": case "findings": case "evidence":
            case "open questions": case "research findings":
                return true;
            default:
                return false;
        }
    }

    static String fallbackTitleInput(String goal, String query) {
        if (query != null && !query.strip().isEmpty()) return query.strip();
        return goal == null ? "" : goal.strip();
    }

    static String safeTitle(String goal, String query) {
        String title = goal == null ? "" : goal.strip();
        if (title.isEmpty()) title = query == null ? "" : query.strip();
        if (title.startsWith("生成")) title = title.substring("生成".length()).strip();
        if (title.startsWith("撰写")) title = title.substring("撰写".length()).strip();
        if (title.isEmpty()) title = "Agent Report";
        return firstRunes(title, 60);
    }

    public static String firstRunes(String s, int n) {
        if (s == null) return "";
        s = s.strip();
        int len = s.codePointCount(0, s.length());
        if (len <= n) return s;
        int end = s.offsetByCodePoints(0, n);
        return s.substring(0, end) + "...";
    }

    public static List<String> sortedKeys(java.util.Map<String, ?> in) {
        if (in == null) return Collections.emptyList();
        return new ArrayList<>(new TreeSet<>(in.keySet()));
    }

    public static List<String> dedupStrings(List<String> in) {
        if (in == null) return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s == null) continue;
            String key = s.strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !seen.add(key)) continue;
            out.add(s);
        }
        return out;
    }

    private static String stripChars(String s, String chars) {
        int start = 0, end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }
}
