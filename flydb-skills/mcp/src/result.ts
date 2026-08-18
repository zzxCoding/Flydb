/**
 * CLI 结果到 MCP tool result 的固定映射（技术决策 §3.1）：
 * - 合法成功信封 → structuredContent=信封、content=同一 JSON 文本、isError 省略；
 * - 合法错误信封 → 同样保留信封与 JSON 文本，isError=true（保留 FLYDB-xxxx）；
 * - CLI 缺失/超时/取消/stdout 非法/协议不兼容/退出码不一致 → isError=true 的
 *   Adapter 诊断，不伪造 FLYDB-xxxx。
 */

import { ADAPTER_ERROR_CODES, adapterDiagnostic } from "./diagnostics.js";
import type { AdapterErrorCode } from "./diagnostics.js";
import { EnvelopeFormatError, parseEnvelope } from "./envelope.js";
import type { CliEnvelope } from "./envelope.js";
import { redact, redactedTail } from "./redact.js";
import type { CliRunResult } from "./cliRunner.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export type McpToolResult = CallToolResult;

export function adapterErrorResult(code: AdapterErrorCode, detail: string,
                                   stderr?: string): McpToolResult {
  const diagnostic = adapterDiagnostic(code,
      redact(detail), stderr === undefined ? undefined : redactedTail(stderr, 2000));
  return {
    content: [{type: "text", text: JSON.stringify(diagnostic)}],
    isError: true,
  };
}

/** 把一次 CLI 执行映射为 tool result；任何进程层失败都收敛为 Adapter 诊断。 */
export function toolResultFromRun(run: CliRunResult): McpToolResult {
  if (run.spawnError !== null) {
    return adapterErrorResult(ADAPTER_ERROR_CODES.cliNotFound,
        `无法启动 flydb CLI（${run.executable}）: ${run.spawnError}`, run.stderr);
  }
  if (run.timedOut) {
    return adapterErrorResult(ADAPTER_ERROR_CODES.cliTimeout,
        `flydb CLI 执行超时（${run.durationMs} ms，已终止子进程）`, run.stderr);
  }
  if (run.aborted) {
    return adapterErrorResult(ADAPTER_ERROR_CODES.cancelled,
        "请求已取消，flydb CLI 子进程被终止", run.stderr);
  }
  if (run.exitCode === null) {
    return adapterErrorResult(ADAPTER_ERROR_CODES.cliNotFound,
        `flydb CLI 进程未正常退出（signal=${String(run.signal)}）`, run.stderr);
  }

  let envelope: CliEnvelope;
  try {
    envelope = parseEnvelope(run.stdout);
  } catch (error) {
    if (error instanceof EnvelopeFormatError) {
      return adapterErrorResult(error.adapterCode, error.message, run.stderr);
    }
    throw error;
  }

  if (envelope.exitCode !== run.exitCode
      || (envelope.status === "success" && run.exitCode !== 0)
      || (envelope.status === "error" && run.exitCode === 0)) {
    return adapterErrorResult(ADAPTER_ERROR_CODES.exitCodeMismatch,
        `进程退出码 ${run.exitCode} 与信封不一致`
        + `（status=${envelope.status}, exitCode=${envelope.exitCode}）`, run.stderr);
  }

  const text = run.stdout.trim();
  if (envelope.status === "error") {
    return {content: [{type: "text", text}], structuredContent: envelope, isError: true};
  }
  return {content: [{type: "text", text}], structuredContent: envelope};
}
