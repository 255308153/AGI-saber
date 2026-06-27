package com.agi.assistant.domain.document;

/**
 * 上传文档解析结果（对应 Go domain/document.ParseResult）。
 *
 * <p>{@code parser} 表示走的哪条管线：{@code plain_text} / {@code pdfplumber} /
 * {@code pdftotext} / {@code pdfbox} 等。{@code needsOCR} 表示页面看起来有内容
 * 但抽取到的文字极少，调用方应给用户提示走 OCR 流程。</p>
 */
public class ParseResult {
    public String filename;
    public String contentType;
    public String parser;
    public String content;
    public int pages;
    public int textChars;
    public boolean needsOCR;

    public ParseResult() {}

    public ParseResult(String filename, String contentType, String parser,
                       String content, int pages, int textChars, boolean needsOCR) {
        this.filename = filename;
        this.contentType = contentType;
        this.parser = parser;
        this.content = content;
        this.pages = pages;
        this.textChars = textChars;
        this.needsOCR = needsOCR;
    }
}
