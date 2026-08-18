package com.flydb.cli.output;

import java.util.regex.Pattern;

/**
 * 密码与 URL 内嵌凭据的统一脱敏（设计 06 §3）。
 *
 * <p>异常消息、dry-run SQL、文本表格与 JSON 机器输出共用同一套规则：
 * {@code password=...} 形式的键值与 URL 中 {@code user:pass@host} 的密码段
 * 一律替换为 {@code ****}；已知明文密码额外整串替换。
 */
public final class SecretRedactor {

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null) return null;
        return text.replaceAll("(?i)(password[=:]\\s*)[^\\s]+", "$1****")
                .replaceAll("(?i)(//[^/:@\\s]+:)[^@\\s]+@", "$1****@");
    }

    public static String redactSecret(String text, String password) {
        String redacted = redact(text);
        if (password != null && !password.isEmpty()) {
            redacted = redacted.replaceAll(Pattern.quote(password), "****");
        }
        return redacted;
    }
}
