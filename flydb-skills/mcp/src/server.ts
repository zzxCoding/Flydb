/**
 * flydb MCP Adapter 入口：stdio transport，注册 v1 工具集。
 *
 * Adapter 只做协议翻译：校验 MCP 输入 → 白名单 CLI 参数 → CliRunner 子进程 →
 * 校验 CLI 信封 → MCP tool result。领域真相始终由 Java Core/CLI 产生。
 * Adapter 自身日志只写 stderr，绝不污染 MCP stdio 的 stdout 通道。
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { realpathSync } from "node:fs";
import { pathToFileURL } from "node:url";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  McpError,
} from "@modelcontextprotocol/sdk/types.js";
import { resolveCliExecutable } from "./cliLocator.js";
import { SubprocessCliRunner } from "./cliRunner.js";
import type { CliRunner } from "./cliRunner.js";
import { handshake } from "./handshake.js";
import { redact } from "./redact.js";
import { adapterErrorResult } from "./result.js";
import { toolResultFromRun } from "./result.js";
import { ADAPTER_ERROR_CODES } from "./diagnostics.js";
import { DEFAULT_TOOLS, FLYDB_TOOLS, WRITE_TOOLS, writesEnabled } from "./tools.js";

declare const ADAPTER_VERSION: string;

export const SERVER_NAME = "flydb-mcp";

/** 默认单次调用超时；FLYDB_MCP_TIMEOUT_MS 可覆盖（正整数，否则用默认值）。 */
export const DEFAULT_TIMEOUT_MS = 600_000;

export function resolveTimeoutMs(env: NodeJS.ProcessEnv,
                                 warn: (message: string) => void): number {
  const raw = env["FLYDB_MCP_TIMEOUT_MS"];
  if (raw === undefined || raw.trim().length === 0) return DEFAULT_TIMEOUT_MS;
  const normalized = raw.trim();
  const value = Number(normalized);
  if (!/^[1-9]\d*$/.test(normalized) || !Number.isSafeInteger(value)) {
    warn(`FLYDB_MCP_TIMEOUT_MS 非法（${raw.trim()}），使用默认值 ${DEFAULT_TIMEOUT_MS} ms`);
    return DEFAULT_TIMEOUT_MS;
  }
  return value;
}

export interface ServerOptions {
  runner: CliRunner;
  env: NodeJS.ProcessEnv;
  timeoutMs: number;
  warn: (message: string) => void;
}

/** 创建已注册 v1 工具集的 Server；握手由调用方先行完成。 */
export function createServer(options: ServerOptions): Server {
  const {runner, env, timeoutMs, warn} = options;
  const writes = writesEnabled(env);
  if (!writes) {
    const raw = env["FLYDB_MCP_ENABLE_WRITES"];
    if (raw !== undefined && raw.trim().length > 0 && raw.trim().toLowerCase() !== "false") {
      warn(`FLYDB_MCP_ENABLE_WRITES=${raw.trim()} 不是合法布尔值，按关闭处理（fail closed）`);
    }
  }
  const registered = writes ? FLYDB_TOOLS : DEFAULT_TOOLS;
  const toolsByName = new Map(registered.map((tool) => [tool.name, tool]));

  const server = new Server(
      {name: SERVER_NAME, version: ADAPTER_VERSION},
      {capabilities: {tools: {listChanged: false}}},
  );

  server.setRequestHandler(ListToolsRequestSchema, () => ({
    tools: registered.map((tool) => ({
      name: tool.name,
      title: tool.annotations.title,
      description: tool.description,
      inputSchema: tool.inputSchema,
      outputSchema: tool.outputSchema,
      annotations: {
        title: tool.annotations.title,
        readOnlyHint: tool.annotations.readOnlyHint,
        destructiveHint: tool.annotations.destructiveHint,
        idempotentHint: tool.annotations.idempotentHint,
        openToWorldHint: tool.annotations.openToWorldHint,
      },
    })),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
    const name = request.params.name;
    const tool = toolsByName.get(name);
    if (tool === undefined) {
      // 未注册（含未开启的写入工具）与未知工具一律走 MCP 协议错误，
      // 不包装成 Flydb 领域错误（技术决策 §3.1）。
      const known = WRITE_TOOLS.some((candidate) => candidate.name === name);
      throw new McpError(-32602, known
          ? `Unknown tool: ${name}（写入工具默认不注册，需操作者设置 FLYDB_MCP_ENABLE_WRITES=true）`
          : `Unknown tool: ${name}`);
    }
    try {
      const build = tool.buildArgs(request.params.arguments ?? {});
      if (!build.ok) {
        return adapterErrorResult(ADAPTER_ERROR_CODES.invalidInput,
            `工具 ${name} 输入非法: ${build.error}`);
      }
      const run = await runner.run(build.args, {
        cwd: build.cwd,
        timeoutMs,
        signal: extra.signal,
      });
      return toolResultFromRun(run);
    } catch (error) {
      return adapterErrorResult(ADAPTER_ERROR_CODES.internal,
          `工具 ${name} 执行异常: ${(error as Error).message}`);
    }
  });

  return server;
}

function stderrLine(message: string): void {
  process.stderr.write(`[flydb-mcp] ${redact(message)}\n`);
}

async function main(): Promise<void> {
  let runner: CliRunner | undefined;
  try {
    const executable = resolveCliExecutable(process.env);
    const timeoutMs = resolveTimeoutMs(process.env, stderrLine);
    runner = new SubprocessCliRunner(executable, timeoutMs);
    const outcome = await handshake(runner);
    stderrLine(`flydb CLI ${outcome.cliVersion}（${executable}），timeout ${timeoutMs} ms`);
    if (writesEnabled(process.env)) {
      stderrLine("写入工具已启用（FLYDB_MCP_ENABLE_WRITES=true）：migrate/baseline/repair/undo 已注册");
    } else {
      stderrLine("写入工具未注册（默认只读）；操作者可通过 FLYDB_MCP_ENABLE_WRITES=true 显式开启");
    }
    const server = createServer({
      runner,
      env: process.env,
      timeoutMs,
      warn: stderrLine,
    });
    const transport = new StdioServerTransport();
    const shutdown = (): void => {
      runner?.dispose?.();
      void server.close().finally(() => process.exit(0));
    };
    process.on("SIGTERM", shutdown);
    process.on("SIGINT", shutdown);
    await server.connect(transport);
    stderrLine(`flydb-mcp ${ADAPTER_VERSION} 已就绪（stdio）`);
  } catch (error) {
    stderrLine(`启动失败: ${(error as Error).message}`);
    runner?.dispose?.();
    process.exit(1);
  }
}

const isDirectRun = (() => {
  if (process.argv[1] === undefined) return false;
  try {
    return import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
  } catch {
    return false;
  }
})();
if (isDirectRun) {
  void main();
}
