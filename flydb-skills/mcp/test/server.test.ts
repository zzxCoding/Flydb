import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { describe, expect, it } from "vitest";
import type { CliRunOptions, CliRunResult, CliRunner } from "../src/cliRunner.js";
import { createServer, DEFAULT_TIMEOUT_MS, resolveTimeoutMs } from "../src/server.js";

/** 记录调用并以脚本回放信封的内存 fake。 */
class FakeCliRunner implements CliRunner {
  readonly calls: Array<{args: string[]; options: CliRunOptions}> = [];

  constructor(private readonly respond: (args: string[]) => {
    stdout: string; exitCode: number; stderr?: string;
  }) {}

  async run(args: string[], options: CliRunOptions = {}): Promise<CliRunResult> {
    this.calls.push({args, options});
    const reply = this.respond(args);
    return {
      executable: "/opt/flydb/bin/flydb",
      args,
      exitCode: reply.exitCode,
      signal: null,
      spawnError: null,
      timedOut: false,
      aborted: false,
      stdout: reply.stdout,
      stderr: reply.stderr ?? "",
      stdoutTruncated: false,
      stderrTruncated: false,
      durationMs: 1,
    };
  }
}

async function withClient(env: NodeJS.ProcessEnv, respond: (args: string[]) => {
  stdout: string; exitCode: number;
}, action: (client: Client, runner: FakeCliRunner) => Promise<void>): Promise<void> {
  const runner = new FakeCliRunner(respond);
  const server = createServer({runner, env, timeoutMs: 5_000, warn: () => undefined});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({name: "test-client", version: "0.0.0"});
  await Promise.all([client.connect(clientTransport), server.connect(serverTransport)]);
  try {
    await action(client, runner);
  } finally {
    await client.close();
    await server.close();
  }
}

const ENVELOPES: Record<string, string> = {
  version: '{"protocolVersion":1,"command":"version","status":"success","exitCode":0,"version":"0.3.0"}',
  info: '{"protocolVersion":1,"command":"info","status":"success","exitCode":0,'
      + '"databaseName":"MySQL","url":"jdbc:mysql://127.0.0.1:3306/app",'
      + '"historyTable":"flydb_schema_history","current":null,"migrations":[]}',
  migrate: '{"protocolVersion":1,"command":"migrate","status":"success","exitCode":0,'
      + '"executed":["V1__init.sql"],"targetVersionReached":"1",'
      + '"totalExecutionTimeMillis":42,"warnings":[]}',
};

function commandOf(args: string[]): string {
  const last = args[args.length - 1] ?? "";
  return last;
}

describe("server（官方 Client 驱动 initialize/list/call）", () => {
  it("超时环境变量必须是完整的正整数字符串", () => {
    const warnings: string[] = [];
    const warn = (message: string): void => { warnings.push(message); };
    expect(resolveTimeoutMs({FLYDB_MCP_TIMEOUT_MS: "1500"}, warn)).toBe(1500);
    expect(resolveTimeoutMs({FLYDB_MCP_TIMEOUT_MS: "1.5"}, warn)).toBe(DEFAULT_TIMEOUT_MS);
    expect(resolveTimeoutMs({FLYDB_MCP_TIMEOUT_MS: "600000ms"}, warn)).toBe(DEFAULT_TIMEOUT_MS);
    expect(warnings).toHaveLength(2);
  });

  it("默认只注册 5 个只读工具，写入工具不在 tools/list", async () => {
    await withClient({}, (args) => ({stdout: ENVELOPES[commandOf(args)] ?? "", exitCode: 0}),
        async (client) => {
          const listed = await client.listTools();
          const names = listed.tools.map((tool) => tool.name);
          expect(names).toEqual([
            "flydb_version", "flydb_info", "flydb_validate",
            "flydb_plan_migrate", "flydb_plan_undo",
          ]);
          const migrate = listed.tools.find((tool) => tool.name === "flydb_plan_migrate");
          expect(migrate?.outputSchema).toBeDefined();
          expect(migrate?.annotations?.readOnlyHint).toBe(true);
        });
  });

  it("FLYDB_MCP_ENABLE_WRITES=true 注册全部九个工具", async () => {
    await withClient({FLYDB_MCP_ENABLE_WRITES: "true"},
        (args) => ({stdout: ENVELOPES[commandOf(args)] ?? "", exitCode: 0}),
        async (client) => {
          const listed = await client.listTools();
          expect(listed.tools).toHaveLength(9);
          const migrate = listed.tools.find((tool) => tool.name === "flydb_migrate");
          expect(migrate?.annotations?.destructiveHint).toBe(true);
          expect(migrate?.annotations?.readOnlyHint).toBe(false);
        });
  });

  it("tools/call 返回 structuredContent 与同一 JSON 文本", async () => {
    await withClient({}, (args) => ({stdout: ENVELOPES[commandOf(args)] ?? "", exitCode: 0}),
        async (client, runner) => {
          const result = await client.callTool({
            name: "flydb_version",
            arguments: {},
          });
          expect(result.isError).toBeUndefined();
          expect(result.structuredContent).toEqual(JSON.parse(ENVELOPES["version"]!));
          const call = runner.calls[0]!;
          expect(call.args).toEqual(["--json", "version"]);
        });
  });

  it("数据库工具传 -c、--driver-download never 与 cwd", async () => {
    await withClient({}, (args) => ({stdout: ENVELOPES[commandOf(args)] ?? "", exitCode: 0}),
        async (client, runner) => {
          await client.callTool({
            name: "flydb_plan_migrate",
            arguments: {workingDirectory: "/work/project", configPath: "/work/project/flydb.conf"},
          });
          const call = runner.calls[0]!;
          expect(call.args).toEqual([
            "--json", "-c", "/work/project/flydb.conf", "--driver-download", "never",
            "--dry-run", "migrate",
          ]);
          expect(call.options.cwd).toBe("/work/project");
        });
  });

  it("CLI 错误信封映射为 isError=true 并保留 FLYDB-xxxx", async () => {
    await withClient({FLYDB_MCP_ENABLE_WRITES: "true"}, () => ({
      stdout: '{"protocolVersion":1,"command":"migrate","status":"error","exitCode":4,'
          + '"error":{"code":"FLYDB-3001","detail":"锁冲突","problems":[]}}',
      exitCode: 4,
    }), async (client) => {
      const result = await client.callTool({
        name: "flydb_migrate",
        arguments: {workingDirectory: "/w", configPath: "/w/flydb.conf"},
      });
      expect(result.isError).toBe(true);
      expect((result.structuredContent as {error?: {code?: string}}).error?.code)
          .toBe("FLYDB-3001");
    });
  });

  it("未注册的写入工具与未知工具返回 MCP 协议错误", async () => {
    await withClient({}, (args) => ({stdout: ENVELOPES[commandOf(args)] ?? "", exitCode: 0}),
        async (client) => {
          await expect(client.callTool({
            name: "flydb_migrate",
            arguments: {workingDirectory: "/w", configPath: "/w/flydb.conf"},
          })).rejects.toThrow(/Unknown tool: flydb_migrate/);
          await expect(client.callTool({name: "flydb_clean", arguments: {}}))
              .rejects.toThrow(/Unknown tool/);
        });
  });

  it("非法输入（相对路径/多余字段）收敛为 FLYDB_MCP-0008 诊断", async () => {
    await withClient({}, (args) => ({stdout: ENVELOPES[commandOf(args)] ?? "", exitCode: 0}),
        async (client, runner) => {
          const result = await client.callTool({
            name: "flydb_validate",
            arguments: {workingDirectory: "relative", configPath: "/abs/flydb.conf"},
          });
          expect(result.isError).toBe(true);
          const content = result.content as Array<{type: string; text?: string}>;
          const first = content[0];
          const diagnostic = JSON.parse(
              first !== undefined && first.type === "text" ? first.text! : "{}");
          expect(diagnostic.adapterError.code).toBe("FLYDB_MCP-0008");
          const versionResult = await client.callTool({
            name: "flydb_version",
            arguments: {rawArgs: ["clean"]},
          });
          expect(versionResult.isError).toBe(true);
          const versionContent = versionResult.content as Array<{type: string; text?: string}>;
          expect(JSON.parse(versionContent[0]?.text ?? "{}").adapterError.code)
              .toBe("FLYDB_MCP-0008");
          expect(runner.calls).toHaveLength(0);
        });
  });
});
