/**
 * 启动时版本握手（技术决策 §3.3）：用定位到的 CLI 执行 `--json version`，
 * 拒绝低于 0.3.0 的产品版本与 protocolVersion !== 1 的协议。
 */

import { redact } from "./redact.js";
import { toolResultFromRun } from "./result.js";
import type { CliRunner } from "./cliRunner.js";

export const MIN_CLI_VERSION = "0.3.0";
export const SUPPORTED_PROTOCOL_VERSION = 1;

export class HandshakeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "HandshakeError";
  }
}

/** 数值化比较 `major.minor.patch`；无法解析为数字的部分忽略（如 -SNAPSHOT 后缀）。 */
export function compareVersions(left: string, right: string): number {
  const parse = (version: string): number[] =>
      version.split(/[.-]/).map((part) => Number.parseInt(part, 10))
          .map((value) => (Number.isNaN(value) ? 0 : value));
  const leftParts = parse(left);
  const rightParts = parse(right);
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index++) {
    const delta = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (delta !== 0) return delta;
  }
  return 0;
}

export interface HandshakeOutcome {
  cliVersion: string;
}

/** 执行 `--json version` 并完成协议与最低版本检查；失败抛出 HandshakeError。 */
export async function handshake(runner: CliRunner,
                                timeoutMs = 30_000): Promise<HandshakeOutcome> {
  const run = await runner.run(["--json", "version"], {timeoutMs});
  const result = toolResultFromRun(run);
  if (result.isError === true) {
    const first = result.content[0];
    const detail = first !== undefined && first.type === "text" ? first.text : "{}";
    throw new HandshakeError(redact(`版本握手失败: ${detail}`));
  }
  const envelope = result.structuredContent;
  const version = envelope?.["version"];
  if (typeof version !== "string" || version.trim().length === 0) {
    throw new HandshakeError("版本握手失败: version 信封缺少 version 字段");
  }
  if (compareVersions(version, MIN_CLI_VERSION) < 0) {
    throw new HandshakeError(
        `flydb CLI 版本过低: ${version}，Adapter 要求 >= ${MIN_CLI_VERSION}`);
  }
  return {cliVersion: version};
}
