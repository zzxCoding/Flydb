import { describe, expect, it } from "vitest";
import { redact, redactedTail } from "../src/redact.js";

describe("redact（与 flydb-cli SecretRedactor 同规则）", () => {
  it("脱敏 password 键值（= 与 :、大小写）", () => {
    expect(redact("password=hunter2")).toBe("password=****");
    expect(redact("PASSWORD: secret123")).toBe("PASSWORD: ****");
    expect(redact("--password=abc")).toBe("--password=****");
  });

  it("脱敏 URL 内嵌凭据", () => {
    expect(redact("jdbc:mysql://flydb:secret@127.0.0.1:3306/app"))
        .toBe("jdbc:mysql://flydb:****@127.0.0.1:3306/app");
  });

  it("普通文本与空值原样返回", () => {
    expect(redact("校验通过")).toBe("校验通过");
    expect(redact(null)).toBe("");
    expect(redact(undefined)).toBe("");
  });

  it("redactedTail 保留尾部并截断", () => {
    expect(redactedTail("abcdef", 10)).toBe("abcdef");
    const tail = redactedTail("a".repeat(100) + "password=x", 20);
    expect(tail.startsWith("...")).toBe(true);
    expect(tail.endsWith("password=****")).toBe(true);
    expect(tail.length).toBe(20);
  });
});
