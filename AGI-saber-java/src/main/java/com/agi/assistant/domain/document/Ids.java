package com.agi.assistant.domain.document;

import java.security.SecureRandom;

/**
 * 文档库 ID 生成器（对应 Go domain/document.NewID）。
 *
 * <p>形如 {@code doc_<hex16>} / {@code ver_<hex16>}，避免 UUID 在日志里太冗长。</p>
 */
public final class Ids {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {}

    public static String newId(String prefix) {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        char[] chars = new char[16];
        for (int i = 0; i < 8; i++) {
            chars[2 * i] = HEX[(b[i] >> 4) & 0xF];
            chars[2 * i + 1] = HEX[b[i] & 0xF];
        }
        return prefix + "_" + new String(chars);
    }
}
