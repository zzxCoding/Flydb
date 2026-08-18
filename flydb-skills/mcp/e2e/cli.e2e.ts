import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { describe, expect, it } from "vitest";

/**
 * 跨运行时端到端（技术决策 §7.2）：真实 flydb-cli 发行包 + 真实 Adapter 进程
 * + 官方 MCP client 通过 stdio 驱动 initialize / tools/list / tools/call。
 * 运行前置：FLYDB_E2E_CLI 指向已构建发行包的 bin/flydb。
 */

const e2eCli = process.env["FLYDB_E2E_CLI"];
const mcpDir = fileURLToPath(new URL("..", import.meta.url));
const serverBundle = resolve(mcpDir, "dist/server.mjs");
const describeE2e = e2eCli ? describe : describe.skip;

async function connectClient(extraEnv: Record<string, string>): Promise<Client> {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [serverBundle],
    env: {...process.env, ...extraEnv},
  });
  const client = new Client({name: "flydb-e2e", version: "0.0.0"});
  await client.connect(transport);
  return client;
}

describeE2e("跨运行时：真实 CLI + MCP stdio", () => {
  it("真实发行包可直接执行 --json version", () => {
    const run = spawnSync(e2eCli!, ["--json", "version"], {encoding: "utf8"});
    expect(run.status).toBe(0);
    const envelope = JSON.parse(run.stdout!.trim());
    expect(envelope.protocolVersion).toBe(1);
    expect(envelope.version).toMatch(/^0\.(3|[4-9])\./);
  });

  it("initialize + tools/list 默认 5 个只读工具，写入工具不注册", async () => {
    const client = await connectClient({FLYDB_CLI: e2eCli});
    try {
      const listed = await client.listTools();
      expect(listed.tools.map((tool) => tool.name)).toEqual([
        "flydb_version", "flydb_info", "flydb_validate",
        "flydb_plan_migrate", "flydb_plan_undo",
      ]);
    } finally {
      await client.close();
    }
  });

  it("FLYDB_MCP_ENABLE_WRITES=true 时 tools/list 包含九个工具", async () => {
    const client = await connectClient({
      FLYDB_CLI: e2eCli,
      FLYDB_MCP_ENABLE_WRITES: "true",
    });
    try {
      const listed = await client.listTools();
      expect(listed.tools).toHaveLength(9);
    } finally {
      await client.close();
    }
  });

  it("tools/call flydb_version 返回真实 CLI 信封", async () => {
    const client = await connectClient({FLYDB_CLI: e2eCli});
    try {
      const result = await client.callTool({name: "flydb_version", arguments: {}});
      expect(result.isError).toBeUndefined();
      const envelope = result.structuredContent as {version?: string};
      expect(envelope.version).toMatch(/^0\.(3|[4-9])\./);
      const content = result.content as Array<{type: string; text?: string}>;
      expect(JSON.parse(content[0]!.text!)).toEqual(envelope);
    } finally {
      await client.close();
    }
  });

  it("CLI 版本过低时拒绝启动（退出码非零，诊断走 stderr）", async () => {
    const dir = mkdtempSync(join(tmpdir(), "flydb-e2e-old-"));
    const fakeOldCli = join(dir, "flydb");
    writeFileSync(fakeOldCli,
        "#!/bin/sh\nprintf '%s\\n' '{\"protocolVersion\":1,\"command\":\"version\","
        + "\"status\":\"success\",\"exitCode\":0,\"version\":\"0.2.1\"}'\n",
        {mode: 0o755});
    const run = spawnSync(process.execPath, [serverBundle], {
      encoding: "utf8",
      env: {...process.env, FLYDB_CLI: fakeOldCli},
    });
    expect(run.status).not.toBe(0);
    expect(run.stderr).toContain("版本过低");
    expect(run.stdout!.trim()).toBe("");
  });

  it("非法输入不触发 CLI 子进程，返回 FLYDB_MCP-0008", async () => {
    const client = await connectClient({FLYDB_CLI: e2eCli});
    try {
      const result = await client.callTool({
        name: "flydb_validate",
        arguments: {workingDirectory: "relative/path", configPath: "/abs/flydb.conf"},
      });
      expect(result.isError).toBe(true);
      const content = result.content as Array<{type: string; text?: string}>;
      const diagnostic = JSON.parse(content[0]!.text!);
      expect(diagnostic.adapterError.code).toBe("FLYDB_MCP-0008");
    } finally {
      await client.close();
    }
  });
});
