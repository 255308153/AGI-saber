package com.agi.assistant.domain.promptctx;

import java.util.ArrayList;
import java.util.List;

/**
 * Source 在填充槽位时遵循的过滤约束（对应 Go promptctx.SlotFilter）。
 */
public class SlotFilter {
    /** 命中其一即可，空表示不限 */
    private List<String> categories = new ArrayList<>();
    /** 必须全部包含 */
    private List<String> requireTags = new ArrayList<>();
    /** 召回综合分阈值 */
    private double minScore;
    /** 单槽位最多返回项数（0 表示不截断） */
    private int topK;
    /** 最大年龄（小时），0 表示不限 */
    private int maxAgeHours;
    /** 单槽位字符预算（粗略以字符数近似 token） */
    private int tokenBudget;

    public SlotFilter() {}

    public SlotFilter(int tokenBudget) { this.tokenBudget = tokenBudget; }

    public List<String> getCategories() { return categories; }
    public void setCategories(List<String> categories) {
        this.categories = categories != null ? categories : new ArrayList<>();
    }
    public List<String> getRequireTags() { return requireTags; }
    public void setRequireTags(List<String> requireTags) {
        this.requireTags = requireTags != null ? requireTags : new ArrayList<>();
    }
    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getMaxAgeHours() { return maxAgeHours; }
    public void setMaxAgeHours(int maxAgeHours) { this.maxAgeHours = maxAgeHours; }
    public int getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(int tokenBudget) { this.tokenBudget = tokenBudget; }
}
