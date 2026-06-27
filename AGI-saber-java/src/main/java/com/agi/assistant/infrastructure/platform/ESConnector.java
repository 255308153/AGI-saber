package com.agi.assistant.infrastructure.platform;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agi.assistant.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;

/**
 * Elasticsearch 连接器（对应 Go infrastructure/platform/es）。
 */
@Component
public class ESConnector {

    private static final Logger log = LoggerFactory.getLogger(ESConnector.class);
    public static final String RAG_INDEX = "rag_chunks";

    private final AppConfig cfg;
    private volatile RestClient restClient;
    private volatile ElasticsearchClient client;
    private volatile String status = "disconnected";

    public ESConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            List<String> addrs = cfg.getElasticsearch().getAddressList();
            if (addrs.isEmpty()) throw new IllegalStateException("ES addresses 为空");
            HttpHost[] hosts = addrs.stream().map(HttpHost::create).toArray(HttpHost[]::new);
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            String user = cfg.getElasticsearch().getUsername();
            String pass = cfg.getElasticsearch().getPassword();
            if (user != null && !user.isEmpty()) {
                creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass));
            }
            restClient = RestClient.builder(hosts)
                    .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(creds))
                    .build();
            restClient.performRequest(new org.elasticsearch.client.Request("GET", "/"));
            client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
            status = "connected";
            log.info("Elasticsearch 已连接: {}", addrs);
        } catch (Exception e) {
            log.warn("Elasticsearch 连接失败: {} (将使用 TF 降级检索)", e.getMessage());
            status = "disconnected";
            if (restClient != null) {
                try { restClient.close(); } catch (Exception ignored) {}
                restClient = null;
            }
        }
    }

    public ElasticsearchClient client() { return client; }
    public boolean available() { return "connected".equals(status) && client != null; }
    public String status() { return status; }

    /** 确保 RAG 索引存在 */
    public void ensureRAGIndex() throws Exception {
        if (client == null) return;
        boolean exists = client.indices().exists(ExistsRequest.of(b -> b.index(RAG_INDEX))).value();
        if (exists) return;
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "pg_id": {"type": "long"},
                      "content": {"type": "text", "analyzer": "standard"},
                      "doc_hash": {"type": "keyword"},
                      "chunk_idx": {"type": "integer"}
                    }
                  }
                }""";
        client.indices().create(CreateIndexRequest.of(c -> c
                .index(RAG_INDEX)
                .withJson(new StringReader(mapping))));
        log.info("ES rag_chunks 索引已创建");
    }

    @PreDestroy
    public void close() {
        if (restClient != null) {
            try { restClient.close(); } catch (Exception ignored) {}
        }
    }
}
