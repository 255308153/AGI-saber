package com.agi.assistant.domain.document;

import java.util.List;

/**
 * 文档库的存储边界（对应 Go domain/document.LibraryRepo）。
 *
 * <p>实现方可以是内存、本地 JSON 或 PostgreSQL；只要遵守"写入立即可读 + 版本不可变"语义即可。</p>
 */
public interface LibraryRepo {

    /** 写入新文档或追加新版本。{@link WriteRequest#getDocumentId()} 为空 → 创建。 */
    WriteResult write(WriteRequest req);

    /** 列出所有非删除文档（按 updatedAt 降序）。 */
    List<Document> list();

    /** 按 documentId 取最新版本（document + version 一同返回）。 */
    DocumentWithVersion get(String documentId);

    /** 按 versionId 取指定历史版本。 */
    DocumentVersion getVersion(String versionId);

    /** Document + 对应版本的元组（避免 Pair 依赖）。 */
    final class DocumentWithVersion {
        public final Document document;
        public final DocumentVersion version;
        public DocumentWithVersion(Document document, DocumentVersion version) {
            this.document = document;
            this.version = version;
        }
    }
}
