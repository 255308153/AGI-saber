package com.agi.assistant.domain.promptctx.source;

import com.agi.assistant.domain.promptctx.ContextItem;
import com.agi.assistant.domain.promptctx.ContextSource;
import com.agi.assistant.domain.promptctx.Query;
import com.agi.assistant.domain.promptctx.Slot;
import com.agi.assistant.domain.promptctx.SlotKind;
import com.agi.assistant.domain.sandbox.Validator;
import com.agi.assistant.domain.sandbox.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装填 Constraints 槽位（对应 Go promptctx.ConstraintsSource）。
 *
 * 来源：sandbox 的静态安全政策（启动时一次性快照，运行期不变）。
 *
 * Java 版从 Validator 通过反射方式获取规则不便，因此这里采用一个简化版本：
 * application 层在初始化时构造一份 Policy 列表传入即可。
 */
public class ConstraintsSource implements ContextSource {

    /** 单条静态安全政策的描述 */
    public static class Policy {
        public final RiskLevel level;
        public final String pattern;
        public final String reason;
        public Policy(RiskLevel level, String pattern, String reason) {
            this.level = level; this.pattern = pattern; this.reason = reason;
        }
    }

    private final List<Policy> policies;

    public ConstraintsSource(List<Policy> policies) {
        this.policies = policies != null ? new ArrayList<>(policies) : new ArrayList<>();
    }

    @Override
    public String id() { return "constraints"; }

    @Override
    public boolean supports(SlotKind kind) { return kind == SlotKind.CONSTRAINTS; }

    @Override
    public List<ContextItem> fetch(Slot slot, Query q) {
        if (policies.isEmpty()) return List.of();
        // Block 优先于 Warn
        List<Policy> ordered = new ArrayList<>();
        for (Policy p : policies) if (p.level == RiskLevel.BLOCK) ordered.add(p);
        for (Policy p : policies) if (p.level != RiskLevel.BLOCK) ordered.add(p);

        int topK = slot.getFilter().getTopK();
        if (topK > 0 && ordered.size() > topK) {
            ordered = ordered.subList(0, topK);
        }

        List<ContextItem> items = new ArrayList<>();
        for (Policy p : ordered) {
            String level = p.level == RiskLevel.BLOCK ? "禁止" : "告警";
            double score = p.level == RiskLevel.BLOCK ? 1.0 : 0.5;
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("level", p.level.value());
            meta.put("pattern", p.pattern);
            items.add(new ContextItem("[" + level + "] " + p.reason, score, id(), meta));
        }
        return items;
    }

    /**
     * 便捷构造：若希望从一份 reason 列表（按 level 分组）构造 Source。
     */
    public static ConstraintsSource fromBuiltinValidator() {
        // 直接使用 domain.sandbox.Validator 的内部规则不容易访问；
        // 这里给一个常见硬约束的精简列表，application 层若需更全规则可手动构造。
        List<Policy> ps = new ArrayList<>();
        ps.add(new Policy(RiskLevel.BLOCK, "rm -rf /", "禁止删除根路径"));
        ps.add(new Policy(RiskLevel.BLOCK, "sudo", "禁止 sudo"));
        ps.add(new Policy(RiskLevel.BLOCK, "curl|wget http", "禁止网络外联（沙箱无网）"));
        ps.add(new Policy(RiskLevel.WARN, ";", "命令链（分号分隔）"));
        ps.add(new Policy(RiskLevel.WARN, "|", "管道符"));
        // 触发 domain.sandbox.Validator 真实规则的访问入口（避免反射），
        // 以便保持依赖路径正确（即使本方法没有用到 Validator 本身）
        Validator.class.getName();
        return new ConstraintsSource(ps);
    }
}
