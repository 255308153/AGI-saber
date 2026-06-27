package com.agi.assistant.service.memory;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.model.MemoryItem;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class LongTermMemory {

    private final List<MemoryItem> items = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> vocabId = new HashMap<>();
    private final List<String> vocab = new ArrayList<>();
    private int nextId = 0;
    private int storeCount = 0;
    private AppConfig.ConsolidationConfig consolidationCfg;

    public void setConsolidationConfig(AppConfig.ConsolidationConfig cfg) {
        this.consolidationCfg = cfg;
    }

    public List<MemoryItem> getItems() { return new ArrayList<>(items); }

    public int size() { return items.size(); }

    // --- Store ---

    public boolean store(String content, double importance, List<Double> embedding) {
        return storeClassified(content, importance, embedding, "general", null, null);
    }

    /**
     * 存入带分类/标签/槽位提示的长期记忆。
     *
     * <p>对应 Go domain/memory/longterm.LongTermMemory.StoreClassified。
     * 复用 {@link #store(String, double, List)} 的 dedup 逻辑，但额外把
     * category/tags/slotHint 写到 MemoryItem 上，便于后续 {@link #recallByFilter} 召回。</p>
     *
     * @return true=新增；false=被去重合并（更新了已有项的 importance）
     */
    public boolean storeClassified(String content, double importance, List<Double> embedding,
                                   String category, List<String> tags, String slotHint) {
        // Dedup check（沿用原 store 行为）
        if (consolidationCfg != null && !items.isEmpty() && embedding != null && !embedding.isEmpty()) {
            for (MemoryItem item : items) {
                if (item.getEmbedding() != null && item.getEmbedding().size() == embedding.size()) {
                    double sim = cosine(embedding, item.getEmbedding());
                    if (sim >= consolidationCfg.getDedupThreshold()) {
                        if (importance > item.getImportance()) {
                            item.setImportance(importance);
                        }
                        // 命中重复时若分类更具体（非 general），允许覆盖
                        if (category != null && !category.isEmpty()
                                && !"general".equals(category)
                                && ("general".equals(item.getCategory()) || item.getCategory() == null)) {
                            item.setCategory(category);
                        }
                        if (tags != null && !tags.isEmpty()) {
                            List<String> merged = new ArrayList<>(item.getTags() == null ? Collections.emptyList() : item.getTags());
                            for (String t : tags) {
                                if (!merged.contains(t)) merged.add(t);
                            }
                            item.setTags(merged);
                        }
                        if (slotHint != null && !slotHint.isEmpty() && (item.getSlotHint() == null || item.getSlotHint().isEmpty())) {
                            item.setSlotHint(slotHint);
                        }
                        item.setLastAccessed(LocalDateTime.now());
                        return false;
                    }
                }
            }
        }

        buildVocab(content);
        MemoryItem item = new MemoryItem(nextId++, content, importance, embedding);
        item.setCategory(category);
        item.setTags(tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        item.setSlotHint(slotHint);
        items.add(item);
        storeCount++;
        return true;
    }

    public void storeItem(MemoryItem item) {
        buildVocab(item.getContent());
        if (item.getId() >= nextId) nextId = item.getId() + 1;
        if (item.getCreatedAt() == null) item.setCreatedAt(LocalDateTime.now());
        if (item.getLastAccessed() == null) item.setLastAccessed(item.getCreatedAt());
        items.add(item);
    }

    public void syncLastItemPGID(int pgId) {
        if (!items.isEmpty() && pgId > 0) {
            items.get(items.size() - 1).setId(pgId);
            if (pgId >= nextId) nextId = pgId + 1;
        }
    }

    public boolean needConsolidation() {
        return consolidationCfg != null
                && consolidationCfg.getTriggerInterval() > 0
                && storeCount >= consolidationCfg.getTriggerInterval();
    }

    // --- Recall ---

    public List<MemoryItem> recall(String query, int topK, List<Double> queryEmbedding) {
        if (items.isEmpty()) return Collections.emptyList();
        final double threshold = 0.4;

        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            MemoryItem item = items.get(i);
            double sim;
            if (queryEmbedding != null && !queryEmbedding.isEmpty()
                    && item.getEmbedding() != null && item.getEmbedding().size() == queryEmbedding.size()) {
                sim = cosine(queryEmbedding, item.getEmbedding());
            } else {
                buildVocab(query);
                double[] qv = textToVector(query);
                double[] iv = textToVector(item.getContent());
                sim = cosineArr(qv, iv);
            }
            double s = sim * 0.7 + item.getImportance() * 0.3;
            if (s >= threshold) {
                item.setLastAccessed(LocalDateTime.now());
                scored.add(new double[]{i, s});
            }
        }
        if (scored.isEmpty()) return Collections.emptyList();

        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        int limit = Math.min(topK, scored.size());
        List<MemoryItem> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            MemoryItem item = items.get((int) scored.get(i)[0]);
            item.setScore(scored.get(i)[1]);
            result.add(item);
        }
        return result;
    }

    /**
     * 带过滤条件的召回（对应 Go domain/memory/longterm.RecallByFilter）。
     *
     * <p>支持 4 个过滤维度：</p>
     * <ul>
     *   <li>{@code categories} —— 只召回指定主类别</li>
     *   <li>{@code requiredTags} —— 必须命中所有指定标签</li>
     *   <li>{@code minScore} —— 最低相似度（默认 0.4）</li>
     *   <li>{@code maxAgeHours} —— 最大年龄（小时）</li>
     * </ul>
     */
    public List<MemoryItem> recallByFilter(String query, List<Double> queryEmbedding, LongTermFilter filter) {
        if (items.isEmpty()) return Collections.emptyList();
        if (filter == null) filter = new LongTermFilter();
        final double threshold = filter.minScore != null ? filter.minScore : 0.4;
        final int wantTopK = filter.topK != null ? filter.topK : 5;
        final LocalDateTime now = LocalDateTime.now();

        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            MemoryItem item = items.get(i);

            // category 过滤
            if (filter.categories != null && !filter.categories.isEmpty()) {
                String c = item.getCategory() == null ? "general" : item.getCategory();
                if (!filter.categories.contains(c)) continue;
            }
            // tags 过滤（AND）
            if (filter.requiredTags != null && !filter.requiredTags.isEmpty()) {
                List<String> have = item.getTags() == null ? Collections.emptyList() : item.getTags();
                boolean ok = true;
                for (String t : filter.requiredTags) {
                    if (!have.contains(t)) { ok = false; break; }
                }
                if (!ok) continue;
            }
            // age 过滤
            if (filter.maxAgeHours != null && item.getCreatedAt() != null) {
                long ageHours = ChronoUnit.HOURS.between(item.getCreatedAt(), now);
                if (ageHours > filter.maxAgeHours) continue;
            }

            // 计算相似度（与 recall 一致）
            double sim;
            if (queryEmbedding != null && !queryEmbedding.isEmpty()
                    && item.getEmbedding() != null && item.getEmbedding().size() == queryEmbedding.size()) {
                sim = cosine(queryEmbedding, item.getEmbedding());
            } else {
                buildVocab(query);
                double[] qv = textToVector(query);
                double[] iv = textToVector(item.getContent());
                sim = cosineArr(qv, iv);
            }
            double s = sim * 0.7 + item.getImportance() * 0.3;
            if (s >= threshold) {
                item.setLastAccessed(now);
                scored.add(new double[]{i, s});
            }
        }
        if (scored.isEmpty()) return Collections.emptyList();

        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        int limit = Math.min(wantTopK, scored.size());
        List<MemoryItem> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            MemoryItem item = items.get((int) scored.get(i)[0]);
            item.setScore(scored.get(i)[1]);
            result.add(item);
        }
        return result;
    }

    // --- Consolidation ---

    public ConsolidationResult consolidate() {
        ConsolidationResult result = new ConsolidationResult();
        if (consolidationCfg == null || items.size() <= 1) return result;
        storeCount = 0;
        Set<Integer> removed = new HashSet<>();

        // Phase 1: Decay
        for (MemoryItem item : items) {
            double days = ChronoUnit.HOURS.between(item.getCreatedAt(), LocalDateTime.now()) / 24.0;
            item.setImportance(item.getImportance() * Math.pow(consolidationCfg.getDecayRate(), days));
        }

        // Phase 2: Dedup + Merge
        for (int i = 0; i < items.size(); i++) {
            if (removed.contains(i)) continue;
            for (int j = i + 1; j < items.size(); j++) {
                if (removed.contains(j)) continue;
                double sim = itemSimilarity(items.get(i), items.get(j));
                if (sim >= consolidationCfg.getDedupThreshold()) {
                    if (items.get(j).getImportance() >= items.get(i).getImportance()) {
                        removed.add(i);
                        result.deduped++;
                        result.deleteFromDB.add(items.get(i).getId());
                    } else {
                        removed.add(j);
                        result.deduped++;
                        result.deleteFromDB.add(items.get(j).getId());
                    }
                } else if (sim >= consolidationCfg.getSimilarityThreshold()) {
                    MemoryItem merged = mergeItems(items.get(i), items.get(j));
                    items.set(i, merged);
                    removed.add(j);
                    result.merged++;
                    result.deleteFromDB.add(items.get(j).getId());
                    result.updateInDB.add(merged);
                }
            }
        }

        // Phase 3: Expire
        for (int i = 0; i < items.size(); i++) {
            if (removed.contains(i)) continue;
            double days = ChronoUnit.HOURS.between(items.get(i).getCreatedAt(), LocalDateTime.now()) / 24.0;
            if (consolidationCfg.getTtlDays() > 0
                    && days > consolidationCfg.getTtlDays()
                    && items.get(i).getImportance() < consolidationCfg.getMinImportance()) {
                removed.add(i);
                result.expired++;
                result.deleteFromDB.add(items.get(i).getId());
            }
        }

        // Rebuild
        List<MemoryItem> newItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!removed.contains(i)) newItems.add(items.get(i));
        }
        items.clear();
        items.addAll(newItems);
        rebuildVocab();
        return result;
    }

    // --- Helper classes ---

    public static class ConsolidationResult {
        public int deduped;
        public int merged;
        public int expired;
        public List<Integer> deleteFromDB = new ArrayList<>();
        public List<MemoryItem> updateInDB = new ArrayList<>();
    }

    // --- Private methods ---

    private void buildVocab(String text) {
        for (String t : tokenize(text)) {
            if (!vocabId.containsKey(t)) {
                vocabId.put(t, vocab.size());
                vocab.add(t);
            }
        }
    }

    private double[] textToVector(String text) {
        double[] vec = new double[vocabId.size()];
        for (String t : tokenize(text)) {
            Integer idx = vocabId.get(t);
            if (idx != null) vec[idx]++;
        }
        return vec;
    }

    private void rebuildVocab() {
        vocabId.clear();
        vocab.clear();
        for (MemoryItem item : items) buildVocab(item.getContent());
    }

    private double itemSimilarity(MemoryItem a, MemoryItem b) {
        if (a.getEmbedding() != null && b.getEmbedding() != null
                && !a.getEmbedding().isEmpty() && a.getEmbedding().size() == b.getEmbedding().size()) {
            return cosine(a.getEmbedding(), b.getEmbedding());
        }
        buildVocab(a.getContent());
        buildVocab(b.getContent());
        return cosineArr(textToVector(a.getContent()), textToVector(b.getContent()));
    }

    private MemoryItem mergeItems(MemoryItem a, MemoryItem b) {
        MemoryItem base = a.getImportance() >= b.getImportance() ? a : b;
        MemoryItem other = base == a ? b : a;

        MemoryItem merged = new MemoryItem();
        merged.setId(base.getId());
        merged.setImportance(Math.max(base.getImportance(), other.getImportance()));
        merged.setEmbedding(base.getEmbedding());
        merged.setCreatedAt(base.getCreatedAt());
        merged.setLastAccessed(LocalDateTime.now());

        if (!base.getContent().contains(other.getContent()) && !other.getContent().contains(base.getContent())) {
            merged.setContent(base.getContent() + "；" + other.getContent());
        } else if (other.getContent().length() > base.getContent().length()) {
            merged.setContent(other.getContent());
        } else {
            merged.setContent(base.getContent());
        }

        // Weighted average embedding
        if (base.getEmbedding() != null && other.getEmbedding() != null
                && !base.getEmbedding().isEmpty() && base.getEmbedding().size() == other.getEmbedding().size()) {
            double wA = base.getImportance(), wB = other.getImportance();
            double total = wA + wB;
            if (total > 0) {
                List<Double> mergedEmb = new ArrayList<>();
                for (int i = 0; i < base.getEmbedding().size(); i++) {
                    mergedEmb.add((base.getEmbedding().get(i) * wA + other.getEmbedding().get(i) * wB) / total);
                }
                merged.setEmbedding(mergedEmb);
            }
        }
        return merged;
    }

    // --- Static utility ---

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                if (word.length() > 0) { tokens.add(word.toString().toLowerCase()); word.setLength(0); }
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else {
                if (word.length() > 0) { tokens.add(word.toString().toLowerCase()); word.setLength(0); }
            }
        }
        if (word.length() > 0) tokens.add(word.toString().toLowerCase());
        return tokens;
    }

    public static double cosine(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double cosineArr(double[] a, double[] b) {
        int len = Math.max(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < len; i++) {
            double ai = i < a.length ? a[i] : 0;
            double bi = i < b.length ? b[i] : 0;
            dot += ai * bi;
            na += ai * ai;
            nb += bi * bi;
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
