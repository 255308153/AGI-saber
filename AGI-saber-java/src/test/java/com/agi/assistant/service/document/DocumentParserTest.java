package com.agi.assistant.service.document;

import com.agi.assistant.domain.document.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本归一化 + 纯文本 parser 行为（对应 Go parser_test 简化版）。
 *
 * <p>PDF 路径依赖外部 pdfplumber / pdftotext / PDFBox，在 CI 没二进制时不强测。</p>
 */
class DocumentParserTest {

    @Test
    void parsePlainTextNormalisesContent() {
        DocumentParser p = new DocumentParser();
        ParseResult r = p.parseBytes("note.txt", "text/plain",
                "  line1  \r\n\r\n  line2  ".getBytes(StandardCharsets.UTF_8));
        assertEquals("plain_text", r.parser);
        assertEquals("line1\n\nline2", r.content);
    }

    @Test
    void emptyDocumentRejected() {
        DocumentParser p = new DocumentParser();
        assertThrows(IllegalArgumentException.class, () ->
                p.parseBytes("empty.txt", "text/plain", new byte[0]));
    }
}
