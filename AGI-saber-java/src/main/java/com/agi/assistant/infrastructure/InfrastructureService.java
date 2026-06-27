package com.agi.assistant.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.agi.assistant.infrastructure.persistence.ChatHistoryRepository;
import com.agi.assistant.infrastructure.persistence.LongTermRepository;
import com.agi.assistant.infrastructure.persistence.PreferenceRepository;
import com.agi.assistant.infrastructure.persistence.RagChunkRepository;
import com.agi.assistant.infrastructure.persistence.SnapshotRepository;
import com.agi.assistant.infrastructure.platform.ESConnector;
import com.agi.assistant.infrastructure.platform.KafkaConnector;
import com.agi.assistant.infrastructure.platform.MilvusConnector;
import com.agi.assistant.infrastructure.platform.Neo4jConnector;
import com.agi.assistant.infrastructure.platform.PostgresConnector;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础设施门面（兼容层）。
 *
 * <p>本类已经被拆分为：</p>
 * <ul>
 *   <li>5 个 Connector（{@code infrastructure.platform.*}）—— 连接管理</li>
 *   <li>5 个 Repository（{@code infrastructure.persistence.*}）—— SQL 访问</li>
 * </ul>
 *
 * <p>本类作为兼容门面保留，所有方法委托到上面 10 个 Bean，避免 controller/agent
 * 等 16+ 个调用点全部需要一次性改造。后续可以让调用方直接依赖具体 Repository
 * 后再删除本门面。</p>
 *
 * <p>仅 RAG 检索相关方法（Milvus 搜索 / ES 搜索 / Milvus 写入 / ES 写入）保留在本类，
 * 因为它们对应的 Repository（如 RAGSearchService）尚未拆出，留作后续工作。</p>
 */
@Service
public class InfrastructureService {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureService.class);

    private final PostgresConnector pg;
    private final MilvusConnector milvusConn;
    private final ESConnector esConn;
    private final KafkaConnector kafkaConn;
    private final Neo4jConnector neo4jConn;

    private final PreferenceRepository preferenceRepo;
    private final LongTermRepository longTermRepo;
    private final ChatHistoryRepository chatHistoryRepo;
    private final SnapshotRepository snapshotRepo;
    private final RagChunkRepository ragChunkRepo;

    public InfrastructureService(PostgresConnector pg,
                                 MilvusConnector milvusConn,
                                 ESConnector esConn,
                                 KafkaConnector kafkaConn,
                                 Neo4jConnector neo4jConn,
                                 PreferenceRepository preferenceRepo,
                                 LongTermRepository longTermRepo,
                                 ChatHistoryRepository chatHistoryRepo,
                                 SnapshotRepository snapshotRepo,
                                 RagChunkRepository ragChunkRepo) {
        this.pg = pg;
        this.milvusConn = milvusConn;
        this.esConn = esConn;
        this.kafkaConn = kafkaConn;
        this.neo4jConn = neo4jConn;
        this.preferenceRepo = preferenceRepo;
        this.longTermRepo = longTermRepo;
        this.chatHistoryRepo = chatHistoryRepo;
        this.snapshotRepo = snapshotRepo;
        this.ragChunkRepo = ragChunkRepo;
    }

    // ================= Status =================

    public Map<String, String> getStatus() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("milvus", milvusConn.status());
        s.put("pg", pg.status());
        s.put("elasticsearch", esConn.status());
        s.put("kafka", kafkaConn.status());
        s.put("neo4j", neo4jConn.status());
        return s;
    }

    public String getMilvusStatus() { return milvusConn.status(); }
    public String getPgStatus() { return pg.status(); }
    public String getEsStatus() { return esConn.status(); }
    public String getKafkaStatus() { return kafkaConn.status(); }

    // ================= Preferences =================

    public void savePreference(String userId, String key, String value) {
        preferenceRepo.save(userId, key, value);
    }

    public Map<String, String> loadPreferences(String userId) {
        return preferenceRepo.loadAll(userId);
    }

    // ================= Long-term Memory =================

    public int saveLongTermItem(String content, double importance, String embeddingJson) {
        return longTermRepo.save(content, importance, embeddingJson);
    }

    public int saveLongTermItemClassified(String content, double importance, String embeddingJson,
                                          String category, String tagsJson, String slotHint) {
        return longTermRepo.saveClassified(content, importance, embeddingJson, category, tagsJson, slotHint);
    }

    public static class LongTermRow {
        public int id; public String content; public double importance;
        public List<Double> embedding; public Timestamp createdAt; public Timestamp lastAccessed;
        public String category; public List<String> tags; public String slotHint;
    }

    public List<LongTermRow> loadLongTermItems() {
        List<LongTermRow> out = new ArrayList<>();
        for (LongTermRepository.Row r : longTermRepo.loadAll()) {
            LongTermRow row = new LongTermRow();
            row.id = r.id; row.content = r.content; row.importance = r.importance;
            row.embedding = r.embedding; row.createdAt = r.createdAt; row.lastAccessed = r.lastAccessed;
            row.category = r.category; row.tags = r.tags; row.slotHint = r.slotHint;
            out.add(row);
        }
        return out;
    }

    public void updateLongTermItem(int id, String content, double importance, String embeddingJson) {
        longTermRepo.update(id, content, importance, embeddingJson);
    }

    public void deleteLongTermItems(List<Integer> ids) {
        longTermRepo.deleteAll(ids);
    }

    // ================= RAG Chunks =================

    public long saveRAGChunk(String docHash, int chunkIdx, String content, String embeddingJson) {
        return ragChunkRepo.save(docHash, chunkIdx, content, embeddingJson);
    }

    public static class ChunkRow {
        public long id; public String content;
    }

    public List<ChunkRow> loadAllRAGChunks() {
        List<ChunkRow> out = new ArrayList<>();
        for (RagChunkRepository.Row r : ragChunkRepo.loadAll()) {
            ChunkRow row = new ChunkRow();
            row.id = r.id; row.content = r.content;
            out.add(row);
        }
        return out;
    }

    public List<ChunkRow> loadRAGChunksByIDs(List<Long> ids) {
        List<ChunkRow> out = new ArrayList<>();
        for (RagChunkRepository.Row r : ragChunkRepo.loadByIds(ids)) {
            ChunkRow row = new ChunkRow();
            row.id = r.id; row.content = r.content;
            out.add(row);
        }
        return out;
    }

    public List<Long> deleteRAGChunksByDocHash(String docHash) {
        return ragChunkRepo.deleteByDocHash(docHash);
    }

    // ================= Chat History =================

    public void saveChatHistory(String role, String content) {
        chatHistoryRepo.save(role, content);
    }

    public static class ChatHistoryRow {
        public String role; public String content; public String createdAt;
    }

    public List<ChatHistoryRow> loadChatHistory(int limit) {
        List<ChatHistoryRow> out = new ArrayList<>();
        for (ChatHistoryRepository.Row r : chatHistoryRepo.load(limit)) {
            ChatHistoryRow row = new ChatHistoryRow();
            row.role = r.role; row.content = r.content; row.createdAt = r.createdAt;
            out.add(row);
        }
        return out;
    }

    // ================= Snapshot =================

    public void saveSnapshot(String taskId, String stateJson) {
        snapshotRepo.save(taskId, stateJson);
    }

    // ================= Kafka =================

    public void publishEvent(String eventType, String payload) {
        kafkaConn.publish(eventType, payload);
    }

    // ================= RAG Infrastructure =================

    public void initRAGInfra(int dim) {
        if (milvusConn.available()) {
            try { milvusConn.ensureRAGCollection(dim); }
            catch (Exception e) { log.warn("Milvus rag_chunks 初始化失败: {}", e.getMessage()); }
        }
        if (esConn.available()) {
            try { esConn.ensureRAGIndex(); }
            catch (Exception e) { log.warn("ES rag_chunks 初始化失败: {}", e.getMessage()); }
        }
    }

    public void insertRAGChunks(List<Long> pgIds, List<String> contents, List<List<Float>> embeddings) {
        MilvusClient milvus = milvusConn.client();
        if (milvus == null || pgIds == null || pgIds.isEmpty()) return;
        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("pg_id", new ArrayList<>(pgIds)));
            fields.add(new InsertParam.Field("content", new ArrayList<>(contents)));
            fields.add(new InsertParam.Field("embedding", new ArrayList<>(embeddings)));
            milvus.insert(InsertParam.newBuilder()
                    .withCollectionName(MilvusConnector.RAG_COLLECTION)
                    .withFields(fields).build());
        } catch (Exception e) {
            log.warn("Milvus 插入 RAG chunks 失败: {}", e.getMessage());
        }
    }

    public void indexRAGChunkInES(long pgId, String content, String docHash, int chunkIdx) {
        ElasticsearchClient es = esConn.client();
        if (es == null) return;
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("pg_id", pgId);
            doc.put("content", content);
            doc.put("doc_hash", docHash);
            doc.put("chunk_idx", chunkIdx);
            es.index(IndexRequest.of(b -> b
                    .index(ESConnector.RAG_INDEX)
                    .id(String.valueOf(pgId))
                    .document(doc)
                    .refresh(Refresh.False)));
        } catch (Exception e) {
            log.warn("ES 索引 RAG chunk (pg_id={}) 失败: {}", pgId, e.getMessage());
        }
    }

    public void deleteRAGChunksFromES(List<Long> pgIds) {
        ElasticsearchClient es = esConn.client();
        if (es == null || pgIds == null || pgIds.isEmpty()) return;
        for (long id : pgIds) {
            try {
                es.delete(d -> d.index(ESConnector.RAG_INDEX).id(String.valueOf(id)));
            } catch (Exception e) {
                log.warn("ES 删除文档 (pg_id={}) 失败: {}", id, e.getMessage());
            }
        }
    }

    public void deleteRAGChunksFromMilvus(List<Long> pgIds) {
        MilvusClient milvus = milvusConn.client();
        if (milvus == null || pgIds == null || pgIds.isEmpty()) return;
        StringBuilder sb = new StringBuilder("pg_id in [");
        for (int i = 0; i < pgIds.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(pgIds.get(i));
        }
        sb.append("]");
        try {
            milvus.delete(DeleteParam.newBuilder()
                    .withCollectionName(MilvusConnector.RAG_COLLECTION)
                    .withExpr(sb.toString()).build());
        } catch (Exception e) {
            log.warn("Milvus 删除 RAG chunks 失败: {}", e.getMessage());
        }
    }

    public static class MilvusHit {
        public long id; public float distance;
        public MilvusHit(long id, float distance) { this.id = id; this.distance = distance; }
    }

    public List<MilvusHit> milvusSearchWithScores(List<Float> vector, int topK) {
        List<MilvusHit> hits = new ArrayList<>();
        MilvusClient milvus = milvusConn.client();
        if (milvus == null) return hits;
        try {
            List<List<Float>> vectors = new ArrayList<>();
            vectors.add(vector);
            R<SearchResults> resp = milvus.search(SearchParam.newBuilder()
                    .withCollectionName(MilvusConnector.RAG_COLLECTION)
                    .withMetricType(MetricType.L2)
                    .withTopK(topK)
                    .withVectors(vectors)
                    .withVectorFieldName("embedding")
                    .withParams("{\"nprobe\":10}")
                    .addOutField("pg_id")
                    .build());
            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus 检索失败: {}", resp.getMessage());
                return hits;
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<?> ids = wrapper.getFieldData("pg_id", 0);
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            int n = scores.size();
            for (int i = 0; i < n; i++) {
                long id = ids != null && i < ids.size() && ids.get(i) instanceof Long ? (Long) ids.get(i)
                        : scores.get(i).getLongID();
                hits.add(new MilvusHit(id, scores.get(i).getScore()));
            }
        } catch (Exception e) {
            log.warn("Milvus 检索异常: {}", e.getMessage());
        }
        return hits;
    }

    public static class ESHit {
        public long pgId; public double score;
        public ESHit(long pgId, double score) { this.pgId = pgId; this.score = score; }
    }

    public List<ESHit> searchRAGChunks(String query, int topK) {
        List<ESHit> hits = new ArrayList<>();
        ElasticsearchClient es = esConn.client();
        if (es == null) return hits;
        try {
            SearchResponse<Map> resp = es.search(SearchRequest.of(s -> s
                    .index(ESConnector.RAG_INDEX)
                    .size(topK)
                    .query(Query.of(q -> q.match(MatchQuery.of(m -> m
                            .field("content").query(query)))))
                    .source(src -> src.filter(f -> f.includes("pg_id")))), Map.class);
            for (Hit<Map> hit : resp.hits().hits()) {
                long pgId;
                Object pgVal = hit.source() == null ? null : hit.source().get("pg_id");
                if (pgVal instanceof Number) pgId = ((Number) pgVal).longValue();
                else if (hit.id() != null) {
                    try { pgId = Long.parseLong(hit.id()); } catch (Exception ex) { continue; }
                } else continue;
                hits.add(new ESHit(pgId, hit.score() == null ? 0 : hit.score()));
            }
        } catch (Exception e) {
            log.warn("ES 检索失败: {}", e.getMessage());
        }
        return hits;
    }
}
