import { describe, expect, it } from "vitest";
import type { CliRunResult } from "../src/cliRunner.js";
import { toolResultFromRun } from "../src/result.js";

function run(overrides: Partial<CliRunResult>): CliRunResult {
  return {
    executable: "/opt/flydb/bin/flydb",
    args: ["--json", "version"],
    exitCode: 0,
    signal: null,
    spawnError: null,
    timedOut: false,
    aborted: false,
    stdout: "",
    stderr: "",
    stdoutTruncated: false,
    stderrTruncated: false,
    durationMs: 10,
    ...overrides,
  };
}

function textOf(result: {content: Array<{type: string; text?: string}>}): string {
  const first = result.content[0];
  return first !== undefined && first.type === "text" ? first.text! : "";
}

function diagnosticOf(result: {content: Array<{type: string; text?: string}>}):
    {adapterError: {code: string; detail: string; stderr?: string}} {
  return JSON.parse(textOf(result));
}

const SUCCESS = '{"protocolVersion":1,"command":"version","status":"success","exitCode":0,"version":"0.3.0"}';
const ERROR = '{"protocolVersion":1,"command":"migrate","status":"error","exitCode":4,'
    + '"error":{"code":"FLYDB-4002","detail":"必须提供 flydb.url","problems":[]}}';

describe("toolResultFromRun（决策 §3.1 映射表）", () => {
  it("合法成功信封 → structuredContent + 同一 JSON 文本，isError 省略", () => {
    const result = toolResultFromRun(run({stdout: SUCCESS + "\n"}));
    expect(result.isError).toBeUndefined();
    expect(result.structuredContent).toEqual(JSON.parse(SUCCESS));
    expect(result.content).toEqual([{type: "text", text: SUCCESS}]);
  });

  it("合法错误信封 → 保留信封与 FLYDB-xxxx，isError=true", () => {
    const result = toolResultFromRun(run({exitCode: 4, stdout: ERROR}));
    expect(result.isError).toBe(true);
    expect((result.structuredContent as {error?: {code?: string}}).error?.code)
        .toBe("FLYDB-4002");
    expect(result.content[0]?.type === "text" && textOf(result)).toBe(ERROR);
  });

  it("CLI 启动失败 → Adapter 诊断 FLYDB_MCP-0001，不伪造领域码", () => {
    const result = toolResultFromRun(run({exitCode: null, spawnError: "spawn ENOENT (code ENOENT)"}));
    expect(result.isError).toBe(true);
    expect(result.structuredContent).toBeUndefined();
    const diagnostic = diagnosticOf(result);
    expect(diagnostic.adapterError.code).toBe("FLYDB_MCP-0001");
    expect(JSON.stringify(diagnostic)).not.toMatch(/"code":"FLYDB-\d/);
  });

  it("超时与取消 → FLYDB_MCP-0002 / 0003", () => {
    const timeout = toolResultFromRun(run({exitCode: null, timedOut: true, signal: "SIGTERM"}));
    expect(diagnosticOf(timeout).adapterError.code).toBe("FLYDB_MCP-0002");
    const cancelled = toolResultFromRun(run({exitCode: null, aborted: true, signal: "SIGTERM"}));
    expect(diagnosticOf(cancelled).adapterError.code).toBe("FLYDB_MCP-0003");
  });

  it("stdout 非法 → FLYDB_MCP-0004，协议不兼容 → 0005", () => {
    const invalid = toolResultFromRun(run({stdout: "oops"}));
    expect(diagnosticOf(invalid).adapterError.code).toBe("FLYDB_MCP-0004");
    const incompatible = toolResultFromRun(run({stdout: SUCCESS.replace('"protocolVersion":1', '"protocolVersion":9')}));
    expect(diagnosticOf(incompatible).adapterError.code).toBe("FLYDB_MCP-0005");
  });

  it("退出码与信封不一致 → FLYDB_MCP-0006", () => {
    const mismatch = toolResultFromRun(run({exitCode: 3, stdout: SUCCESS}));
    expect(diagnosticOf(mismatch).adapterError.code).toBe("FLYDB_MCP-0006");
    const successWithNonZero = toolResultFromRun(
        run({exitCode: 2, stdout: SUCCESS.replace('"exitCode":0', '"exitCode":2')}));
    expect(diagnosticOf(successWithNonZero).adapterError.code)
        .toBe("FLYDB_MCP-0006");
    const errorWithZero = toolResultFromRun(
        run({exitCode: 0, stdout: ERROR.replace('"exitCode":4', '"exitCode":0')}));
    expect(diagnosticOf(errorWithZero).adapterError.code).toBe("FLYDB_MCP-0006");
  });

  it("Adapter 诊断脱敏 stderr 中的密码与 URL 凭据", () => {
    const result = toolResultFromRun(run({
      exitCode: null,
      spawnError: "boom",
      stderr: "connecting with password=topsecret to jdbc:mysql://u:p@h/db",
    }));
    const text = textOf(result);
    expect(text).not.toContain("topsecret");
    expect(text).toContain("password=****");
    expect(text).toContain("u:****@h");
  });
});
