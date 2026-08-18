/**
 * CliRunner：MCP tool 与 `flydb --json` 进程契约之间的唯一内部 seam（技术决策 §3.2）。
 *
 * 深模块边界：参数数组启动、禁止 shell、显式 cwd、分离并限制 stdout/stderr、
 * 超时与取消的 SIGTERM/强杀、子进程回收。对外只暴露 `run(args, options)`。
 * 生产实现使用真实子进程；单元测试注入内存 fake。
 */

import { spawn } from "node:child_process";

export interface CliRunOptions {
  /** 子进程工作目录（数据库工具固定为 tool 的 workingDirectory）。 */
  cwd?: string;
  /** 超时毫秒数；到期先 SIGTERM，宽限期后 SIGKILL。 */
  timeoutMs?: number;
  /** MCP 取消信号；触发与超时相同的终止流程。 */
  signal?: AbortSignal;
}

export interface CliRunResult {
  executable: string;
  args: string[];
  /** 进程退出码；启动失败或被信号杀死时为 null。 */
  exitCode: number | null;
  /** 进程被信号终止时的信号名。 */
  signal: string | null;
  /** 启动失败原因（如 ENOENT），成功启动为 null。 */
  spawnError: string | null;
  timedOut: boolean;
  aborted: boolean;
  stdout: string;
  stderr: string;
  stdoutTruncated: boolean;
  stderrTruncated: boolean;
  durationMs: number;
}

export interface CliRunner {
  run(args: string[], options?: CliRunOptions): Promise<CliRunResult>;
  /** 终止所有仍在运行的子进程（server 关闭时调用）。 */
  dispose?(): void;
}

const MAX_STDOUT_CHARS = 8 * 1024 * 1024;
const MAX_STDERR_CHARS = 1024 * 1024;
const KILL_GRACE_MS = 5000;

class BoundedCollector {
  private buffer = "";
  truncated = false;

  constructor(private readonly maxChars: number) {}

  append(chunk: string): void {
    if (this.truncated) return;
    if (this.buffer.length + chunk.length > this.maxChars) {
      this.buffer += chunk.slice(0, this.maxChars - this.buffer.length);
      this.truncated = true;
      return;
    }
    this.buffer += chunk;
  }

  get text(): string {
    return this.buffer;
  }
}

/** 以真实子进程执行 CLI；不会因为子进程失败而 reject，失败信息在结果字段里。 */
export class SubprocessCliRunner implements CliRunner {
  private readonly executable: string;
  private readonly defaultTimeoutMs: number;
  private readonly active = new Set<ReturnType<typeof spawn>>();

  constructor(executable: string, defaultTimeoutMs: number) {
    this.executable = executable;
    this.defaultTimeoutMs = defaultTimeoutMs;
  }

  run(args: string[], options: CliRunOptions = {}): Promise<CliRunResult> {
    const startedAt = Date.now();
    const timeoutMs = options.timeoutMs ?? this.defaultTimeoutMs;
    return new Promise<CliRunResult>((resolve) => {
      const stdout = new BoundedCollector(MAX_STDOUT_CHARS);
      const stderr = new BoundedCollector(MAX_STDERR_CHARS);
      const child = spawn(this.executable, args, {
        cwd: options.cwd,
        stdio: ["ignore", "pipe", "pipe"],
        shell: false,
        windowsHide: true,
        env: process.env,
      });
      const result: CliRunResult = {
        executable: this.executable,
        args,
        exitCode: null,
        signal: null,
        spawnError: null,
        timedOut: false,
        aborted: false,
        stdout: "",
        stderr: "",
        stdoutTruncated: false,
        stderrTruncated: false,
        durationMs: 0,
      };
      let killTimer: NodeJS.Timeout | undefined;
      let graceTimer: NodeJS.Timeout | undefined;
      let settled = false;

      const settle = (exitCode: number | null, signal: string | null) => {
        if (settled) return;
        settled = true;
        this.active.delete(child);
        if (killTimer !== undefined) clearTimeout(killTimer);
        if (graceTimer !== undefined) clearTimeout(graceTimer);
        if (options.signal !== undefined) {
          options.signal.removeEventListener("abort", onAbort);
        }
        result.exitCode = exitCode;
        result.signal = signal;
        result.stdout = stdout.text;
        result.stderr = stderr.text;
        result.stdoutTruncated = stdout.truncated;
        result.stderrTruncated = stderr.truncated;
        result.durationMs = Date.now() - startedAt;
        resolve(result);
      };

      function onAbort(): void {
        result.aborted = true;
        terminate();
      }

      function terminate(): void {
        if (child.exitCode !== null || child.signalCode !== null || child.killed) return;
        child.kill("SIGTERM");
        graceTimer = setTimeout(() => {
          if (child.exitCode === null && child.signalCode === null) {
            child.kill("SIGKILL");
          }
        }, KILL_GRACE_MS);
      }

      child.stdout?.setEncoding("utf8").on("data", (chunk: string) => stdout.append(chunk));
      child.stderr?.setEncoding("utf8").on("data", (chunk: string) => stderr.append(chunk));
      child.on("error", (error: NodeJS.ErrnoException) => {
        result.spawnError = error.code !== undefined
            ? `${error.message} (code ${error.code})`
            : error.message;
        // spawn 失败后不会触发 close，这里直接收敛
        settle(null, null);
      });
      child.on("close", (code, signal) => settle(code, signal));

      if (timeoutMs > 0) {
        killTimer = setTimeout(() => {
          result.timedOut = true;
          terminate();
        }, timeoutMs);
      }
      if (options.signal !== undefined) {
        if (options.signal.aborted) {
          result.aborted = true;
          terminate();
        } else {
          options.signal.addEventListener("abort", onAbort, {once: true});
        }
      }
      this.active.add(child);
    });
  }

  dispose(): void {
    for (const child of this.active) {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
      }
    }
    this.active.clear();
  }
}
