package com.agi.assistant.domain.document;

/** 文档写入结果（对应 Go domain/document.WriteResult）。 */
public class WriteResult {
    public final Document document;
    public final DocumentVersion version;
    public final boolean created;

    public WriteResult(Document document, DocumentVersion version, boolean created) {
        this.document = document;
        this.version = version;
        this.created = created;
    }
}
