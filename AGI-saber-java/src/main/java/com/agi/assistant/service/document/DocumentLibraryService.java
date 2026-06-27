package com.agi.assistant.service.document;

import com.agi.assistant.domain.document.Document;
import com.agi.assistant.domain.document.DocumentVersion;
import com.agi.assistant.domain.document.LibraryRepo;
import com.agi.assistant.domain.document.WriteRequest;
import com.agi.assistant.domain.document.WriteResult;
import com.agi.assistant.service.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 文档库应用服务（对应 Go application/chat/documents.go 中 WriteDocument / ListDocuments / IngestDocument）。
 *
 * <p>负责：</p>
 * <ul>
 *   <li>把 {@link WriteRequest} 落库到 {@link LibraryRepo}</li>
 *   <li>可选地把新版本 markdown 同步写入 RAG（默认 doc_agent 走这条路）</li>
 *   <li>列举 / 读取 / 重新入库已有版本</li>
 * </ul>
 *
 * <p>RagService 是 lazy 注入，因为 {@code UnifiedAgentService} 在初始化时也会持有 RagService，
 * 这里再注入会形成循环依赖；用 {@code @Lazy} 打破。</p>
 */
@Service
public class DocumentLibraryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLibraryService.class);

    private final LibraryRepo repo;
    private final RagService rag;

    public DocumentLibraryService(LibraryRepo repo, @Lazy RagService rag) {
        this.repo = repo;
        this.rag = rag;
    }

    /** 暴露 repo 给 sub-agents（避免它们直接持有 repo）。 */
    public LibraryRepo repo() { return repo; }

    /** 落库 + 可选 RAG 入库。返回结果包含 document/version/created/ingest。 */
    public Result writeDocument(WriteRequest req, boolean ingestToRAG) {
        WriteResult wr = repo.write(req);
        Result out = new Result(wr.document, wr.version, wr.created);
        if (ingestToRAG && rag != null) {
            try {
                Map.Entry<Integer, String> ingest = rag.ingest(wr.version.getContentMd());
                out.ingestChunks = ingest.getKey();
                out.ingestDocHash = ingest.getValue();
            } catch (Exception e) {
                log.warn("ingest doc to RAG failed: {}", e.getMessage());
            }
        }
        return out;
    }

    public List<Document> list() { return repo.list(); }

    public LibraryRepo.DocumentWithVersion get(String documentId) {
        return repo.get(documentId);
    }

    /** 重新把某个文档（或具体版本）写入 RAG。 */
    public Map.Entry<Integer, String> reingest(String documentId, String versionId) {
        DocumentVersion ver;
        if (versionId != null && !versionId.isEmpty()) {
            ver = repo.getVersion(versionId);
        } else {
            ver = repo.get(documentId).version;
        }
        if (rag == null) throw new IllegalStateException("RagService not available");
        return rag.ingest(ver.getContentMd());
    }

    /** 写入结果（合并 document.WriteResult + 可选的 RAG ingest 摘要）。 */
    public static class Result {
        public final Document document;
        public final DocumentVersion version;
        public final boolean created;
        public Integer ingestChunks;
        public String ingestDocHash;

        public Result(Document document, DocumentVersion version, boolean created) {
            this.document = document;
            this.version = version;
            this.created = created;
        }
    }
}
