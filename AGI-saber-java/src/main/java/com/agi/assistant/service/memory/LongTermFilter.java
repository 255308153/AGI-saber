package com.agi.assistant.service.memory;

import java.util.List;

/**
 * 长期记忆过滤条件。所有字段为可选（null = 不限制）。
 *
 * <p>对应 Go domain/memory/longterm.Filter，用于支持按 category / tags / age 召回。</p>
 *
 * 典型用法：
 * <pre>
 *   // 只召回身份/偏好类记忆
 *   ltm.recallByFilter(query, emb, LongTermFilter.builder()
 *           .categories(List.of("identity", "preference"))
 *           .topK(5)
 *           .build());
 * </pre>
 */
public class LongTermFilter {
    /** 允许的 category 列表，null = 全部 */
    public List<String> categories;
    /** 必须命中的 tag 列表（AND 关系），null = 不限 */
    public List<String> requiredTags;
    /** 最低相似度（默认 0.4） */
    public Double minScore;
    /** topK，默认 5 */
    public Integer topK;
    /** 最大年龄（小时），null = 不限 */
    public Integer maxAgeHours;

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final LongTermFilter f = new LongTermFilter();
        public Builder categories(List<String> v) { f.categories = v; return this; }
        public Builder requiredTags(List<String> v) { f.requiredTags = v; return this; }
        public Builder minScore(double v) { f.minScore = v; return this; }
        public Builder topK(int v) { f.topK = v; return this; }
        public Builder maxAgeHours(int v) { f.maxAgeHours = v; return this; }
        public LongTermFilter build() { return f; }
    }
}
