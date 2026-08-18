import { describe, expect, it } from "vitest";
import type { CliRunResult, CliRunner } from "../src/cliRunner.js";
import { compareVersions, handshake, HandshakeError } from "../src/handshake.js";

function fakeRunner(stdout: string, exitCode = 0, spawnError: string | null = null): CliRunner {
  return {
    async run(): Promise<CliRunResult> {
      return {
        executable: "/opt/flydb/bin/flydb",
        args: [],
        exitCode: spawnError === null ? exitCode : null,
        signal: null,
        spawnError,
        timedOut: false,
        aborted: false,
        stdout,
        stderr: "",
        stdoutTruncated: false,
        stderrTruncated: false,
        durationMs: 5,
      };
    },
  };
}

describe("handshake", () => {
  it("接受 protocolVersion=1 且版本 >= 0.3.0 的 CLI", async () => {
    const outcome = await handshake(fakeRunner(
        '{"protocolVersion":1,"command":"version","status":"success","exitCode":0,"version":"0.3.0"}'));
    expect(outcome.cliVersion).toBe("0.3.0");
  });

  it("拒绝版本过低的 CLI（FLYDB_MCP-0009 语义）", async () => {
    await expect(handshake(fakeRunner(
        '{"protocolVersion":1,"command":"version","status":"success","exitCode":0,"version":"0.2.1"}')))
        .rejects.toThrow(HandshakeError);
  });

  it("CLI 缺失/信封非法时报 HandshakeError 而不是崩溃", async () => {
    await expect(handshake(fakeRunner("", 127, "spawn ENOENT"))).rejects.toThrow(HandshakeError);
    await expect(handshake(fakeRunner("garbage"))).rejects.toThrow(HandshakeError);
  });
});

describe("compareVersions", () => {
  it("数值化比较并容忍后缀", () => {
    expect(compareVersions("0.3.0", "0.2.1")).toBeGreaterThan(0);
    expect(compareVersions("0.3.1-SNAPSHOT", "0.3.0")).toBeGreaterThan(0);
    expect(compareVersions("1.0.0", "0.9.9")).toBeGreaterThan(0);
    expect(compareVersions("0.3.0", "0.3.0")).toBe(0);
  });
});
