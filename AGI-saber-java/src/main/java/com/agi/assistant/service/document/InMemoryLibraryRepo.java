package com.agi.assistant.service.document;

import com.agi.assistant.domain.document.Document;
import com.agi.assistant.domain.document.DocumentVersion;
import com.agi.assistant.domain.document.Ids;
import com.agi.assistant.domain.document.LibraryRepo;
import com.agi.assistant.domain.document.WriteRequest;
import com.agi.assistant.domain.document.WriteResult;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版文档库（对应 Go infrastructure/persistence/documentrepo 的 local 模式）。
 *
 * <p>用于本地开发 / 测试，没有 PG 依赖；单实例进程级共享，不做持久化。
 * 之后接入 PG 时再加一份 {@code PgDocumentLibraryRepo} 替换即可。</p>
 */
@Repository
public class InMemoryLibraryRepo implements LibraryRepo {

    private final Object mu = new Object();
    private final Map<String, Document> docs = new LinkedHashMap<>();
    /** documentId → versions（按 version 升序追加） */
    private final Map<String, List<DocumentVersion>> versions = new LinkedHashMap<>();
    /** versionId → version 引用（便于 GetVersion） */
    private final Map<String, DocumentVersion> versionIndex = new LinkedHashMap<>();

    @Override
    public WriteResult write(WriteRequest in) {
        WriteRequest req = WriteRequest.normalize(in);
        if (req.getTitle().isEmpty()) throw new IllegalArgumentException("title is required");
        if (req.getContentMd().isEmpty()) throw new IllegalArgumentException("content_md is required");

        synchronized (mu) {
            Instant now = Instant.now();
            boolean created = req.getDocumentId().isEmpty();
            String docId = req.getDocumentId();
            Document doc;
            int versionNo;
            if (created) {
                docId = Ids.newId("doc");
                doc = new Document();
                doc.setId(docId);
                doc.setTitle(req.getTitle());
                doc.setDocType(req.getDocType());
                doc.setSource(req.getSource());
                doc.setStatus(Document.STATUS_ACTIVE);
                doc.setCreatedBy(req.getCreatedBy());
                doc.setCreatedAt(now);
                doc.setUpdatedAt(now);
                docs.put(docId, doc);
                versions.put(docId, new ArrayList<>());
                versionNo = 1;
            } else {
                doc = docs.get(docId);
                if (doc == null) throw new IllegalArgumentException("document not found: " + docId);
                doc.setTitle(req.getTitle());
                doc.setDocType(req.getDocType());
                doc.setSource(req.getSource());
                doc.setStatus(Document.STATUS_ACTIVE);
                doc.setUpdatedAt(now);
                versionNo = versions.get(docId).size() + 1;
            }

            DocumentVersion v = new DocumentVersion();
            v.setId(Ids.newId("ver"));
            v.setDocumentId(docId);
            v.setVersion(versionNo);
            v.setContentMd(req.getContentMd());
            v.setSummary(req.getSummary());
            v.setMetadata(req.getMetadata());
            v.setCreatedAt(now);
            versions.get(docId).add(v);
            versionIndex.put(v.getId(), v);

            doc.setLatestVersion(versionNo);
            doc.setLatestVersionId(v.getId());

            return new WriteResult(copyOf(doc), copyOf(v), created);
        }
    }

    @Override
    public List<Document> list() {
        synchronized (mu) {
            List<Document> result = new ArrayList<>(docs.size());
            for (Document d : docs.values()) {
                if (!"deleted".equals(d.getStatus())) result.add(copyOf(d));
            }
            result.sort(Comparator.comparing(Document::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return result;
        }
    }

    @Override
    public DocumentWithVersion get(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("document_id is required");
        }
        synchronized (mu) {
            Document d = docs.get(documentId);
            if (d == null) throw new IllegalArgumentException("document not found: " + documentId);
            DocumentVersion v = versionIndex.get(d.getLatestVersionId());
            if (v == null) throw new IllegalStateException("latest version missing for " + documentId);
            return new DocumentWithVersion(copyOf(d), copyOf(v));
        }
    }

    @Override
    public DocumentVersion getVersion(String versionId) {
        if (versionId == null || versionId.isEmpty()) {
            throw new IllegalArgumentException("version_id is required");
        }
        synchronized (mu) {
            DocumentVersion v = versionIndex.get(versionId);
            if (v == null) throw new IllegalArgumentException("version not found: " + versionId);
            return copyOf(v);
        }
    }

    // ─────────────────────────── 浅拷贝（避免外部修改内部状态） ───────────────────────────

    private static Document copyOf(Document d) {
        Document c = new Document();
        c.setId(d.getId());
        c.setTitle(d.getTitle());
        c.setDocType(d.getDocType());
        c.setSource(d.getSource());
        c.setStatus(d.getStatus());
        c.setCreatedBy(d.getCreatedBy());
        c.setCreatedAt(d.getCreatedAt());
        c.setUpdatedAt(d.getUpdatedAt());
        c.setLatestVersion(d.getLatestVersion());
        c.setLatestVersionId(d.getLatestVersionId());
        return c;
    }

    private static DocumentVersion copyOf(DocumentVersion v) {
        DocumentVersion c = new DocumentVersion();
        c.setId(v.getId());
        c.setDocumentId(v.getDocumentId());
        c.setVersion(v.getVersion());
        c.setContentMd(v.getContentMd());
        c.setSummary(v.getSummary());
        c.setMetadata(new LinkedHashMap<>(v.getMetadata()));
        c.setCreatedAt(v.getCreatedAt());
        return c;
    }
}
