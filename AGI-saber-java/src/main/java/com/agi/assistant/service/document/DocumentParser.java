package com.agi.assistant.service.document;

import com.agi.assistant.domain.document.ParseResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 文档解析器（对应 Go domain/document/parser.go）。
 *
 * <p>纯文本直接归一化，PDF 走"外部命令优先 → PDFBox 兜底"管线：</p>
 * <ol>
 *   <li>{@code pdfplumber}（Python 子进程） —— 表格 / 多列效果最好</li>
 *   <li>{@code pdftotext}（poppler 命令） —— 速度最快，layout 模式</li>
 *   <li>{@code Apache PDFBox} —— 纯 Java 兜底</li>
 * </ol>
 *
 * <p>当 PDF 页数 &gt; 0 但抽到的文字长度低于 {@value #MIN_USEFUL_PDF_TEXT_RUNES}，
 * 视为扫描件，设置 {@link ParseResult#needsOCR} = true 由上层提示用户走 OCR。</p>
 */
@Service
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);
    private static final int MIN_USEFUL_PDF_TEXT_RUNES = 80;
    private static final long PDF_EXTRACT_TIMEOUT_SECONDS = 30;
    private static final Pattern HYPHEN_LINE_BREAK = Pattern.compile("([A-Za-z])-\\n([A-Za-z])");

    /** 入口：自动按 content-type + 扩展名分流到对应管线。 */
    public ParseResult parseBytes(String filename, String contentType, byte[] data) {
        String mediaType = normalizeContentType(contentType);
        String ext = extensionOf(filename);
        if ("application/pdf".equals(mediaType) || ".pdf".equals(ext)) {
            return parsePDF(filename, mediaType, data);
        }
        String text = normalizeText(new String(data, StandardCharsets.UTF_8));
        if (text.isBlank()) {
            throw new IllegalArgumentException("uploaded document is empty");
        }
        return new ParseResult(filename, mediaType, "plain_text", text, 0, runeLen(text), false);
    }

    // ─────────────────────────── PDF 解析 ───────────────────────────

    private ParseResult parsePDF(String filename, String contentType, byte[] data) {
        ExternalExtraction ext = extractPDFExternal(data);
        if (ext != null && ext.text != null && !ext.text.isBlank()) {
            String text = normalizeText(ext.text);
            int chars = runeLen(text);
            return new ParseResult(filename, contentType, ext.parser, text, ext.pages, chars,
                    ext.pages > 0 && chars < MIN_USEFUL_PDF_TEXT_RUNES);
        }

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(data))) {
            int pages = doc.getNumberOfPages();
            StringBuilder b = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.isBlank()) continue;
                if (b.length() > 0) b.append("\n\n");
                b.append("--- page ").append(i).append(" ---\n").append(pageText);
            }
            String text = normalizeText(b.toString());
            int chars = runeLen(text);
            if (chars == 0) {
                ParseResult r = new ParseResult(filename, contentType, "pdfbox", "", pages, 0, true);
                throw new PdfNeedsOcrException("pdf contains no extractable text; OCR is required", r);
            }
            return new ParseResult(filename, contentType, "pdfbox", text, pages, chars,
                    pages > 0 && chars < MIN_USEFUL_PDF_TEXT_RUNES);
        } catch (PdfNeedsOcrException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("parse pdf failed: " + e.getMessage(), e);
        }
    }

    private static class ExternalExtraction {
        String text;
        int pages;
        String parser;
        ExternalExtraction(String text, int pages, String parser) {
            this.text = text; this.pages = pages; this.parser = parser;
        }
    }

    private ExternalExtraction extractPDFExternal(byte[] data) {
        ExternalExtraction r = extractPDFWithPlumber(data);
        if (r != null) return r;
        return extractPDFWithPdftotext(data);
    }

    private ExternalExtraction extractPDFWithPlumber(byte[] data) {
        Path tmp = writeTempPDF(data);
        if (tmp == null) return null;
        try {
            String script =
                    "import json, sys\n" +
                    "import pdfplumber\n" +
                    "path = sys.argv[1]\n" +
                    "texts = []\n" +
                    "with pdfplumber.open(path) as pdf:\n" +
                    "    pages = len(pdf.pages)\n" +
                    "    for i, page in enumerate(pdf.pages, 1):\n" +
                    "        text = page.extract_text(x_tolerance=1, y_tolerance=3) or ''\n" +
                    "        if text.strip():\n" +
                    "            texts.append(f'--- page {i} ---\\n{text}')\n" +
                    "print(json.dumps({'pages': pages, 'text': '\\n\\n'.join(texts)}, ensure_ascii=False))\n";
            for (String python : pythonCandidates()) {
                try {
                    Process p = new ProcessBuilder(python, "-c", script, tmp.toString())
                            .redirectErrorStream(false)
                            .start();
                    if (!p.waitFor(PDF_EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        continue;
                    }
                    if (p.exitValue() != 0) continue;
                    byte[] out = p.getInputStream().readAllBytes();
                    if (out.length == 0) continue;
                    String raw = new String(out, StandardCharsets.UTF_8).trim();
                    PlumberOut po = decodePlumber(raw);
                    if (po != null && po.text != null && !po.text.isBlank()) {
                        return new ExternalExtraction(po.text, po.pages, "pdfplumber");
                    }
                } catch (Exception ignored) { /* try next python */ }
            }
            return null;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private ExternalExtraction extractPDFWithPdftotext(byte[] data) {
        Path tmp = writeTempPDF(data);
        if (tmp == null) return null;
        try {
            String exe = findExecutable("pdftotext");
            if (exe == null) return null;
            Process p = new ProcessBuilder(exe, "-layout", "-enc", "UTF-8", tmp.toString(), "-")
                    .redirectErrorStream(false)
                    .start();
            if (!p.waitFor(PDF_EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            byte[] out = p.getInputStream().readAllBytes();
            if (out.length == 0) return null;
            String text = new String(out, StandardCharsets.UTF_8);
            if (text.isBlank()) return null;
            return new ExternalExtraction(text, 0, "pdftotext");
        } catch (Exception e) {
            return null;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────── 文本归一化 ───────────────────────────

    static String normalizeText(String s) {
        if (s == null) return "";
        s = s.replace(" ", "")
             .replace("\r\n", "\n")
             .replace('\r', '\n')
             .replace("­", "");
        s = HYPHEN_LINE_BREAK.matcher(s).replaceAll("$1$2");
        String[] lines = s.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean blank = false;
        for (String raw : lines) {
            String line = collapseInlineSpace(raw).trim();
            if (line.isEmpty()) {
                if (!blank && !out.isEmpty()) {
                    out.add("");
                    blank = true;
                }
                continue;
            }
            out.add(line);
            blank = false;
        }
        return String.join("\n", out).trim();
    }

    private static String collapseInlineSpace(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevSpace) b.append(' ');
                prevSpace = true;
            } else {
                b.append(c);
                prevSpace = false;
            }
        }
        return b.toString();
    }

    private static String normalizeContentType(String s) {
        if (s == null) return "";
        int idx = s.indexOf(';');
        String main = (idx >= 0 ? s.substring(0, idx) : s).trim().toLowerCase(Locale.ROOT);
        return main;
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx).toLowerCase(Locale.ROOT);
    }

    private static int runeLen(String s) {
        if (s == null) return 0;
        return s.codePointCount(0, s.length());
    }

    // ─────────────────────────── 外部子进程辅助 ───────────────────────────

    private static Path writeTempPDF(byte[] data) {
        try {
            File f = File.createTempFile("agi-saber-", ".pdf");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            return f.toPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> pythonCandidates() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String key : new String[]{"PDF_EXTRACT_PYTHON", "PDF_PYTHON"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank() && seen.add(v.trim())) out.add(v.trim());
        }
        String exe = findExecutable("python3");
        if (exe != null && seen.add(exe)) out.add(exe);
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            String cand = home + "/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3";
            if (Files.exists(Path.of(cand)) && seen.add(cand)) out.add(cand);
        }
        return out;
    }

    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(dir, name);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    // ─────────────────────────── pdfplumber JSON 解析 ───────────────────────────

    private static class PlumberOut {
        int pages;
        String text;
    }

    private static PlumberOut decodePlumber(String raw) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode n = m.readTree(raw);
            PlumberOut o = new PlumberOut();
            o.pages = n.path("pages").asInt(0);
            o.text = n.path("text").asText("");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 抽取后页面有内容但文本极短 → 需要 OCR；调用方可用 {@link #getResult()} 拿到部分元数据。 */
    public static class PdfNeedsOcrException extends RuntimeException {
        private final ParseResult result;
        public PdfNeedsOcrException(String message, ParseResult result) {
            super(message);
            this.result = result;
        }
        public ParseResult getResult() { return result; }
    }
}
