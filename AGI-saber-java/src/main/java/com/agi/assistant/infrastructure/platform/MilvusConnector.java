package com.agi.assistant.infrastructure.platform;

import com.agi.assistant.config.AppConfig;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Milvus 连接器（对应 Go infrastructure/platform/milvus）。
 *
 * <p>负责：连接 + 探活 + RAG collection 初始化。failure-isolated：连接失败不抛异常，
 * {@link #client()} 返回 null。</p>
 */
@Component
public class MilvusConnector {

    private static final Logger log = LoggerFactory.getLogger(MilvusConnector.class);
    public static final String RAG_COLLECTION = "rag_chunks";

    private final AppConfig cfg;
    private volatile MilvusClient client;
    private volatile String status = "disconnected";

    public MilvusConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            ConnectParam param = ConnectParam.newBuilder()
                    .withHost(cfg.getMilvus().getHost())
                    .withPort(cfg.getMilvus().getPort())
                    .withConnectTimeout(5, TimeUnit.SECONDS)
                    .build();
            MilvusServiceClient c = new MilvusServiceClient(param);
            R<Boolean> probe = c.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName("__probe__").build());
            if (probe.getStatus() != R.Status.Success.getCode()
                    && probe.getStatus() != R.Status.CollectionNotExists.getCode()) {
                // probe 也算可用（部分版本对未存在 collection 返回非 Success）
            }
            client = c;
            status = "connected";
            log.info("Milvus 已连接: {}", cfg.getMilvusAddr());
        } catch (Exception e) {
            log.warn("Milvus 连接失败: {} (将使用内存向量库)", e.getMessage());
            status = "disconnected";
        }
    }

    public MilvusClient client() { return client; }
    public boolean available() { return "connected".equals(status) && client != null; }
    public String status() { return status; }

    /** 确保 RAG collection 存在；维度不匹配时自动重建 */
    public void ensureRAGCollection(int dim) {
        if (client == null) return;
        R<Boolean> hasResp = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(RAG_COLLECTION).build());
        boolean has = hasResp.getStatus() == R.Status.Success.getCode() && Boolean.TRUE.equals(hasResp.getData());

        if (has) {
            R<io.milvus.grpc.DescribeCollectionResponse> desc = client.describeCollection(
                    DescribeCollectionParam.newBuilder().withCollectionName(RAG_COLLECTION).build());
            boolean needRecreate = false;
            if (desc.getStatus() == R.Status.Success.getCode()) {
                DescCollResponseWrapper w = new DescCollResponseWrapper(desc.getData());
                for (FieldType f : w.getFields()) {
                    if ("embedding".equals(f.getName()) && f.getDataType() == DataType.FloatVector) {
                        Map<String, String> tp = f.getTypeParams();
                        String existing = tp == null ? "" : tp.getOrDefault("dim", "");
                        if (!String.valueOf(dim).equals(existing)) {
                            log.warn("Milvus rag_chunks 维度不匹配 (现有={}, 期望={})，重建", existing, dim);
                            needRecreate = true;
                        }
                    }
                    if ("id".equals(f.getName()) && f.isPrimaryKey()) {
                        log.warn("Milvus rag_chunks 主键为 id (应为 pg_id)，重建");
                        needRecreate = true;
                    }
                }
            }
            if (needRecreate) {
                client.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(RAG_COLLECTION).build());
                has = false;
            }
            if (has) return;
        }

        FieldType pgIdField = FieldType.newBuilder().withName("pg_id")
                .withDataType(DataType.Int64).withPrimaryKey(true).withAutoID(false).build();
        FieldType contentField = FieldType.newBuilder().withName("content")
                .withDataType(DataType.VarChar).withMaxLength(4096).build();
        FieldType embField = FieldType.newBuilder().withName("embedding")
                .withDataType(DataType.FloatVector).withDimension(dim).build();
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(RAG_COLLECTION).withShardsNum(1)
                .addFieldType(pgIdField).addFieldType(contentField).addFieldType(embField).build();
        R<RpcStatus> r = client.createCollection(createParam);
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("create rag_chunks 失败: " + r.getMessage());
        }
        try {
            client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.L2)
                    .withExtraParam("{\"nlist\":128}")
                    .build());
        } catch (Exception e) {
            log.warn("Milvus rag_chunks 索引创建失败: {}", e.getMessage());
        }
        try {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION).build());
        } catch (Exception e) {
            log.warn("Milvus rag_chunks 加载失败: {}", e.getMessage());
        }
        log.info("Milvus rag_chunks collection 已创建");
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(3); } catch (Exception ignored) {}
        }
    }
}
