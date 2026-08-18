/**
 * Adapter 诊断的脱敏边界，规则与 flydb-cli 的 SecretRedactor 一致：
 * `password=...` / `password: ...` 形式的键值与 URL 中 `user:pass@host` 的密码段
 * 一律替换为 `****`。Adapter 自身诊断（stderr 摘录、路径、错误消息）必须先过这里。
 */

export function redact(text: string | null | undefined): string {
  if (text === null || text === undefined) return "";
  return text
      .replace(/(password[=:]\s*)\S+/gi, "$1****")
      .replace(/(\/\/[^/:@\s]+:)[^@\s]+@/g, "$1****@");
}

/** 摘录尾部诊断（保留最近的输出），并完成脱敏。 */
export function redactedTail(text: string | null | undefined, maxChars: number): string {
  const value = redact(text);
  if (value.length <= maxChars) return value;
  return "..." + value.slice(value.length - (maxChars - 3));
}
