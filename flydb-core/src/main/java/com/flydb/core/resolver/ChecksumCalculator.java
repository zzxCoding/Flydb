package com.flydb.core.resolver;

import java.util.zip.CRC32;

/**
 * 迁移脚本校验和计算器（设计 02 §7）。
 *
 * <p>算法：{@link CRC32}（{@code java.util.zip.CRC32}，跨 JVM/平台稳定）——替代旧原型的
 * {@code String.hashCode()}（缺陷 #2：跨 JVM 不稳定、只写不查）。
 *
 * <p>输入处理（均在<b>占位符替换之前</b>，校验和反映受控的脚本文件本身）：
 * <ol>
 *   <li>剥离前导 UTF-8 BOM（{@code EF BB BF}）。</li>
 *   <li>行尾归一化：{@code \r\n} 与孤立 {@code \r} 统一为 {@code \n}。
 *       对 UTF-8/ASCII 安全——{@code 0x0D}/{@code 0x0A} 不会出现在多字节序列中。</li>
 *   <li>对归一化后的字节流计算 CRC32，低 32 位按有符号 {@code int} 返回。</li>
 * </ol>
 *
 * <p>校验和基于<b>字节</b>而非解码后的字符，故与具体字符编码解释无关（编码只影响读取/解析阶段）。
 */
public final class ChecksumCalculator {

    private static final byte UTF8_BOM_BYTE_0 = (byte) 0xEF;
    private static final byte UTF8_BOM_BYTE_1 = (byte) 0xBB;
    private static final byte UTF8_BOM_BYTE_2 = (byte) 0xBF;

    private ChecksumCalculator() {
    }

    /**
     * @param rawBytes 脚本原始字节（可能含 UTF-8 BOM、CRLF）
     * @return CRC32（有符号 int）
     */
    public static int checksum(byte[] rawBytes) {
        if (rawBytes == null) {
            throw new IllegalArgumentException("rawBytes 不能为 null");
        }
        byte[] stripped = stripUtf8Bom(rawBytes);
        byte[] normalized = normalizeLineEndings(stripped);

        CRC32 crc = new CRC32();
        crc.update(normalized);
        return (int) crc.getValue();
    }

    private static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3
                && bytes[0] == UTF8_BOM_BYTE_0
                && bytes[1] == UTF8_BOM_BYTE_1
                && bytes[2] == UTF8_BOM_BYTE_2) {
            byte[] out = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, out, 0, out.length);
            return out;
        }
        return bytes;
    }

    private static byte[] normalizeLineEndings(byte[] bytes) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r') {
                out.write('\n');
                // 跳过 \r\n 中的 \n，避免重复
                if (i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    i++;
                }
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toByteArray();
    }
}
