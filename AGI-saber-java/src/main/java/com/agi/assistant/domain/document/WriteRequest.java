package com.agi.assistant.domain.document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档写入请求（对应 Go domain/document.WriteRequest）。
 *
 * <p>{@code documentId} 为空时创建新文档；非空时在该文档下追加新版本。</p>
 */
public class WriteRequest {

    private String documentId = "";
    private String title;
    private String docType;
    private String source;
    private String createdBy;
    private String contentMd;
    private String summary = "";
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public WriteRequest() {}

    public WriteRequest(String title, String docType, String source, String createdBy,
                        String contentMd, String summary, Map<String, Object> metadata) {
        this.title = title;
        this.docType = docType;
        this.source = source;
        this.createdBy = createdBy;
        this.contentMd = contentMd;
        if (summary != null) this.summary = summary;
        if (metadata != null) this.metadata = metadata;
    }

    /** 归一化：去前后空格 + 默认值（doc_type=note / source=agent_generated / created_by=agent）。 */
    public static WriteRequest normalize(WriteRequest in) {
        WriteRequest r = in == null ? new WriteRequest() : in;
        r.title = trim(r.title);
        r.docType = trim(r.docType);
        r.source = trim(r.source);
        r.createdBy = trim(r.createdBy);
        r.contentMd = trim(r.contentMd);
        r.summary = trim(r.summary);
        if (r.docType.isEmpty()) r.docType = "note";
        if (r.source.isEmpty()) r.source = Document.SOURCE_AGENT;
        if (r.createdBy.isEmpty()) r.createdBy = "agent";
        if (r.metadata == null) r.metadata = new LinkedHashMap<>();
        return r;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId == null ? "" : documentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary == null ? "" : summary; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }
}
