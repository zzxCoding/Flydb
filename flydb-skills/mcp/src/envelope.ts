/**
 * flydb `--json` 输出信封的解析与校验（机器契约设计 10 / JSON 输出参考）。
 *
 * 校验核心不变量：恰好一个 JSON 文档、`protocolVersion` 必须为 1、
 * `status` 只能是 success/error、`exitCode` 为整数、`command` 为字符串或 null。
 * 载荷字段按命令不同而存在；同一 protocolVersion 内 CLI 可能追加字段，
 * 这里绝不能因为未知字段而拒绝信封（消费者必须忽略未知字段）。
 */

export const PROTOCOL_VERSION = 1;

import type { AdapterErrorCode } from "./diagnostics.js";

export interface ValidationProblem {
  code: string;
  detail: string;
}

export interface EnvelopeError {
  code: string | null;
  detail: string | null;
  problems: ValidationProblem[];
}

export interface CliEnvelope {
  protocolVersion: number;
  command: string | null;
  status: "success" | "error";
  exitCode: number;
  error?: EnvelopeError;
  [payload: string]: unknown;
}

/** 信封格式错误，携带 Adapter 诊断码（不伪造 FLYDB-xxxx 领域错误码）。 */
export class EnvelopeFormatError extends Error {
  readonly adapterCode: AdapterErrorCode;

  constructor(adapterCode: AdapterErrorCode, message: string) {
    super(message);
    this.name = "EnvelopeFormatError";
    this.adapterCode = adapterCode;
  }
}

const ADAPTER_CODES = {
  invalidStdout: "FLYDB_MCP-0004",
  unsupportedProtocol: "FLYDB_MCP-0005",
  invalidEnvelope: "FLYDB_MCP-0007",
} as const;

export const ENVELOPE_ADAPTER_CODES = ADAPTER_CODES;

function fail(adapterCode: AdapterErrorCode, message: string): never {
  throw new EnvelopeFormatError(adapterCode, message);
}

/** 解析并校验 stdout：必须恰好是一个合法信封 JSON 文档。 */
export function parseEnvelope(stdout: string): CliEnvelope {
  const text = stdout.trim();
  if (text.length === 0) {
    fail(ADAPTER_CODES.invalidStdout, "CLI stdout 为空，未输出 JSON 信封");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    fail(ADAPTER_CODES.invalidStdout,
        `CLI stdout 不是单个合法 JSON 文档: ${(error as Error).message}`);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    fail(ADAPTER_CODES.invalidStdout, "CLI stdout 不是 JSON 对象信封");
  }
  const envelope = parsed as Record<string, unknown>;
  if (envelope["protocolVersion"] !== PROTOCOL_VERSION) {
    fail(ADAPTER_CODES.unsupportedProtocol,
        `protocolVersion 不兼容: 期望 ${PROTOCOL_VERSION}，实际 ${JSON.stringify(envelope["protocolVersion"])}`);
  }
  const status = envelope["status"];
  if (status !== "success" && status !== "error") {
    fail(ADAPTER_CODES.invalidEnvelope, `status 必须是 success 或 error: ${JSON.stringify(status)}`);
  }
  const exitCode = envelope["exitCode"];
  if (typeof exitCode !== "number" || !Number.isInteger(exitCode)) {
    fail(ADAPTER_CODES.invalidEnvelope, `exitCode 必须是整数: ${JSON.stringify(exitCode)}`);
  }
  const command = envelope["command"];
  if (command !== null && typeof command !== "string") {
    fail(ADAPTER_CODES.invalidEnvelope, `command 必须是字符串或 null: ${JSON.stringify(command)}`);
  }
  if (status === "error") {
    const error = envelope["error"];
    if (typeof error !== "object" || error === null) {
      fail(ADAPTER_CODES.invalidEnvelope, "error 信封必须携带 error 对象");
    }
    const detail = (error as Record<string, unknown>)["detail"];
    if (detail !== null && detail !== undefined && typeof detail !== "string") {
      fail(ADAPTER_CODES.invalidEnvelope, "error.detail 必须是字符串或 null");
    }
  }
  return envelope as CliEnvelope;
}
