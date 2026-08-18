/**
 * Adapter 层诊断码。区别于 Flydb CLI 的 `FLYDB-xxxx` 领域错误码：
 * 这些码只表示 Adapter 自身的进程编排、协议或输入问题，绝不用于伪造 CLI 领域错误。
 */

export const ADAPTER_ERROR_CODES = {
  /** 找不到或无法启动 flydb CLI 可执行文件 */
  cliNotFound: "FLYDB_MCP-0001",
  /** CLI 超时被终止 */
  cliTimeout: "FLYDB_MCP-0002",
  /** 请求被取消，CLI 子进程被终止 */
  cancelled: "FLYDB_MCP-0003",
  /** stdout 非法（空、非 JSON、多个文档或非对象） */
  invalidStdout: "FLYDB_MCP-0004",
  /** protocolVersion 不兼容 */
  unsupportedProtocol: "FLYDB_MCP-0005",
  /** 进程退出码与信封 exitCode/status 不一致 */
  exitCodeMismatch: "FLYDB_MCP-0006",
  /** 信封核心字段校验失败 */
  invalidEnvelope: "FLYDB_MCP-0007",
  /** MCP tool 输入未通过白名单校验 */
  invalidInput: "FLYDB_MCP-0008",
  /** CLI 产品版本低于 Adapter 支持下限 */
  cliVersionTooOld: "FLYDB_MCP-0009",
  /** Adapter 内部错误（不应发生） */
  internal: "FLYDB_MCP-0099",
} as const;

export type AdapterErrorCode = (typeof ADAPTER_ERROR_CODES)[keyof typeof ADAPTER_ERROR_CODES];

export interface AdapterDiagnostic {
  adapter: "flydb-mcp";
  adapterError: {
    code: AdapterErrorCode;
    detail: string;
    stderr?: string;
  };
}

export function adapterDiagnostic(code: AdapterErrorCode, detail: string,
                                  stderr?: string): AdapterDiagnostic {
  const diagnostic: AdapterDiagnostic = {
    adapter: "flydb-mcp",
    adapterError: {code, detail},
  };
  if (stderr !== undefined && stderr.length > 0) {
    diagnostic.adapterError.stderr = stderr;
  }
  return diagnostic;
}
