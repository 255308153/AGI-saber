package com.agi.assistant.domain.document;

import java.time.Instant;

/**
 * 文档库的稳定记录（对应 Go domain/document.Document）。
 *
 * <p>一条 {@code Document} 记录可能包含多个不可变 {@link DocumentVersion}；
 * {@link #latestVersionId} 与 {@link #latestVersion} 缓存最新版指针，避免每次查询都拉版本表。</p>
 */
public class Document {

    public static final String STATUS_ACTIVE = "active";
    public static final String SOURCE_AGENT = "agent_generated";
    public static final String SOURCE_UPLOAD = "user_upload";

    private String id;
    private String title;
    private String docType;
    private String source;
    private String status = STATUS_ACTIVE;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private int latestVersion;
    private String latestVersionId;

    public Document() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getLatestVersion() { return latestVersion; }
    public void setLatestVersion(int latestVersion) { this.latestVersion = latestVersion; }
    public String getLatestVersionId() { return latestVersionId; }
    public void setLatestVersionId(String latestVersionId) { this.latestVersionId = latestVersionId; }
}
