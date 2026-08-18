import { mkdtempSync, realpathSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { SubprocessCliRunner } from "../src/cliRunner.js";

/**
 * 用真实子进程测试 CliRunner：以 node（或带空格路径的 shell 脚本）扮演 flydb CLI，
 * 覆盖成功、失败退出码、启动失败、超时、取消与 stdout/stderr 分离。
 */

const node = process.execPath;
const created: string[] = [];

function tempDir(prefix: string): string {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  created.push(dir);
  return dir;
}

afterEach(() => {
  created.length = 0;
});

describe("SubprocessCliRunner", () => {
  it("成功执行并分离 stdout/stderr", async () => {
    const runner = new SubprocessCliRunner(node, 30_000);
    const run = await runner.run(["-e",
      "process.stdout.write(JSON.stringify({protocolVersion:1}));process.stderr.write('诊断')"]);
    expect(run.exitCode).toBe(0);
    expect(run.stdout).toBe('{"protocolVersion":1}');
    expect(run.stderr).toBe("诊断");
    expect(run.spawnError).toBeNull();
    expect(run.timedOut).toBe(false);
  });

  it("保留非零退出码", async () => {
    const runner = new SubprocessCliRunner(node, 30_000);
    const run = await runner.run(["-e", "process.exit(4)"]);
    expect(run.exitCode).toBe(4);
  });

  it("启动失败（ENOENT）收敛为 spawnError 而不是 reject", async () => {
    const runner = new SubprocessCliRunner("/nonexistent/flydb", 30_000);
    const run = await runner.run(["--json", "version"]);
    expect(run.exitCode).toBeNull();
    expect(run.spawnError).toContain("ENOENT");
  });

  it("超时先 SIGTERM 并收敛 timedOut", async () => {
    const runner = new SubprocessCliRunner(node, 150);
    const run = await runner.run(["-e", "setInterval(()=>{},1000)"]);
    expect(run.timedOut).toBe(true);
    expect(run.exitCode).not.toBe(0);
    expect(run.durationMs).toBeGreaterThanOrEqual(140);
    runner.dispose();
  });

  it("AbortSignal 取消终止子进程", async () => {
    const runner = new SubprocessCliRunner(node, 30_000);
    const controller = new AbortController();
    const pending = runner.run(["-e", "setInterval(()=>{},1000)"], {signal: controller.signal});
    setTimeout(() => controller.abort(), 120);
    const run = await pending;
    expect(run.aborted).toBe(true);
    expect(run.exitCode).not.toBe(0);
  });

  it("路径带空格与参数带空格不经 shell 直传", async () => {
    const dir = tempDir("flydb runner space-");
    const scriptPath = join(dir, "fake flydb");
    if (process.platform === "win32") {
      writeFileSync(scriptPath + ".cmd", "@echo off\r\necho {\"ok\":true}\r\n");
    } else {
      writeFileSync(scriptPath, "#!/bin/sh\nprintf '%s' \"$1\"\n", {mode: 0o755});
    }
    const executable = process.platform === "win32" ? scriptPath + ".cmd" : scriptPath;
    const runner = new SubprocessCliRunner(executable, 30_000);
    const run = await runner.run(["--json -c /path with space/flydb.conf"]);
    expect(run.exitCode).toBe(0);
    expect(run.stdout).toBe("--json -c /path with space/flydb.conf");
    expect(run.spawnError).toBeNull();
  });

  it("显式 cwd 生效", async () => {
    const dir = tempDir("flydb-runner-cwd-");
    const runner = new SubprocessCliRunner(node, 30_000);
    const run = await runner.run(["-e", "process.stdout.write(process.cwd())"], {cwd: dir});
    // macOS 上 /var 是 /private/var 的符号链接，按 realpath 比较
    expect(run.stdout).toBe(realpathSync(dir));
  });
});
