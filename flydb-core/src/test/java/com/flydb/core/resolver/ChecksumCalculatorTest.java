package com.flydb.core.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * ChecksumCalculator 测试（设计 02 §7、08 §1）。
 *
 * <p>核心契约：CRC32（跨 JVM/平台稳定）+ 行尾归一化（\r\n 与孤立 \r → \n）+ 剥离 UTF-8 BOM。
 * 替代旧原型的 {@code String.hashCode()}（缺陷 #2：跨 JVM 不稳定且只写不查）。
 */
class ChecksumCalculatorTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void same_content_produces_same_checksum() {
        int first = ChecksumCalculator.checksum(utf8("CREATE TABLE t (id INT);"));
        int second = ChecksumCalculator.checksum(utf8("CREATE TABLE t (id INT);"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void different_content_produces_different_checksum() {
        int a = ChecksumCalculator.checksum(utf8("CREATE TABLE a (id INT);"));
        int b = ChecksumCalculator.checksum(utf8("CREATE TABLE b (id INT);"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void crlf_normalized_to_lf_yields_same_checksum() {
        int lf = ChecksumCalculator.checksum(utf8("line1\nline2\nline3"));
        int crlf = ChecksumCalculator.checksum(utf8("line1\r\nline2\r\nline3"));

        assertThat(crlf).isEqualTo(lf);
    }

    @Test
    void lone_cr_normalized_to_lf() {
        int lf = ChecksumCalculator.checksum(utf8("line1\nline2"));
        int cr = ChecksumCalculator.checksum(utf8("line1\rline2"));

        assertThat(cr).isEqualTo(lf);
    }

    @Test
    void utf8_bom_is_stripped() {
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = utf8("SELECT 1;");
        byte[] bomPrefixed = concat(withBom, content);

        assertThat(ChecksumCalculator.checksum(bomPrefixed))
                .isEqualTo(ChecksumCalculator.checksum(content));
    }

    @Test
    void matches_plain_crc32_of_normalized_bytes_as_signed_int() {
        // 验证实现确为 CRC32，且按有符号 int 返回（高位置 1 时为负数）
        byte[] content = utf8("INSERT INTO t VALUES (1);\r\nINSERT INTO t VALUES (2);\r");
        byte[] normalized = utf8("INSERT INTO t VALUES (1);\nINSERT INTO t VALUES (2);\n");

        CRC32 crc = new CRC32();
        crc.update(normalized);
        int expected = (int) crc.getValue();

        assertThat(ChecksumCalculator.checksum(content)).isEqualTo(expected);
    }

    @Test
    void empty_content_is_stable_zero() {
        // CRC32 of empty = 0
        assertThat(ChecksumCalculator.checksum(new byte[0])).isZero();
        assertThat(ChecksumCalculator.checksum(utf8(""))).isZero();
    }

    @Test
    void unicode_content_is_stable() {
        byte[] content = utf8("-- 中文注释：创建用户表\nCREATE TABLE 用户 (编号 INT);");

        int first = ChecksumCalculator.checksum(content);
        int second = ChecksumCalculator.checksum(content);

        assertThat(first).isEqualTo(second);
        // 与直接 CRC32（已归一化，无 BOM/CRLF）一致
        CRC32 crc = new CRC32();
        crc.update(content);
        assertThat(first).isEqualTo((int) crc.getValue());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
