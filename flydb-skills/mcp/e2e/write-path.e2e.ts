import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

/**
 * 写入路径一次性测试库端到端（技术决策 §7.2，绝不指向生产）：
 * 仅在 FLYDB_E2E_WRITE=1 且 Docker 可用时运行。PostgreSQL 容器 + 真实 CLI 发行包
 * + MCP 写入工具，覆盖默认态不注册、开启态闭环与 baseline/repair/undo fixture。
 * 前置与 FLYDB_E2E_CLI 相同。
 */

const enabled = process.env["FLYDB_E2E_WRITE"] === "1";
const e2eCli = process.env["FLYDB_E2E_CLI"];
const describeWrite = enabled && e2eCli ? describe : describe.skip;

const mcpDir = fileURLToPath(new URL("..", import.meta.url));
const serverBundle = join(mcpDir, "dist/server.mjs");
const distRoot = join(e2eCli ?? ".", "..", "..");
const containerName = "flydb-mcp-e2e-pg";
const pgPassword = "flydb-e2e";

function sh(command: string, options: Record<string, unknown> = {}): string {
  return execFileSync("sh", ["-c", command], {encoding: "utf8", ...options}) as string;
}

function connectClient(extraEnv: Record<string, string>): Promise<Client> {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [serverBundle],
    env: {...process.env, ...extraEnv},
  });
  const client = new Client({name: "flydb-write-e2e", version: "0.0.0"});
  return client.connect(transport).then(() => client);
}

function projectSetup(name: string, migrations: Record<string, string>,
                      extraConfig: string[] = []): {
  workdir: string; configPath: string; port: string;
} {
  const port = sh(`docker port ${containerName} | head -1 | sed 's/.*://g'`).trim();
  const workdir = mkdtempSync(join(tmpdir(), `flydb-e2e-${name}-`));
  const migrationsDir = join(workdir, "db");
  mkdirSync(migrationsDir);
  for (const [file, sql] of Object.entries(migrations)) {
    writeFileSync(join(migrationsDir, file), sql);
  }
  const configPath = join(workdir, "flydb.conf");
  writeFileSync(configPath, [
    `flydb.url=jdbc:postgresql://127.0.0.1:${port}/${name}`,
    "flydb.user=postgres",
    `flydb.table=${name}_history`,
    `flydb.locations=filesystem:${migrationsDir}`,
    ...extraConfig,
    "",
  ].join("\n"));
  sh(`docker exec ${containerName} createdb -U postgres ${name}`);
  return {workdir, configPath, port};
}

function callTool(client: Client, name: string, paths: {workdir: string; configPath: string},
                  tool: string): Promise<Record<string, unknown>> {
  return client.callTool({
    name: tool,
    arguments: {workingDirectory: paths.workdir, configPath: paths.configPath},
  }).then((result) => result.structuredContent as Record<string, unknown>);
}

const V1 = {
  "V1__init.sql": "CREATE TABLE e2e_marker (id INT PRIMARY KEY);\nINSERT INTO e2e_marker VALUES (1);\n",
  "U1__drop_init.sql": "DROP TABLE e2e_marker;\n",
};

beforeAll(() => {
  sh(`docker rm -f ${containerName} 2>/dev/null || true`);
  sh(`docker run -d --name ${containerName} -e POSTGRES_PASSWORD=${pgPassword} `
      + "-p 127.0.0.1::5432 postgres:16-alpine");
  for (let attempt = 0; attempt < 60; attempt++) {
    const ready = spawnSync("docker", ["exec", containerName, "pg_isready", "-U", "postgres"],
        {encoding: "utf8"});
    if (ready.status === 0) return;
    sh("sleep 1");
  }
  throw new Error("PostgreSQL 容器未就绪");
}, 180_000);

afterAll(() => {
  sh(`docker rm -f ${containerName} 2>/dev/null || true`);
});

describeWrite("写入路径：默认态与开启态", () => {
  it("默认态：写入工具不在 tools/list，直接调用返回未知工具错误", async () => {
    const client = await connectClient({FLYDB_CLI: e2eCli});
    try {
      const listed = await client.listTools();
      expect(listed.tools.map((tool) => tool.name)).not.toContain("flydb_migrate");
      await expect(client.callTool({
        name: "flydb_migrate",
        arguments: {workingDirectory: "/tmp", configPath: "/tmp/flydb.conf"},
      })).rejects.toThrow(/Unknown tool/);
    } finally {
      await client.close();
    }
  });

  it("开启态闭环：plan_migrate → migrate → info → validate", async () => {
    const setup = projectSetup("mcp_loop", V1);
    const client = await connectClient({
      FLYDB_CLI: e2eCli,
      FLYDB_MCP_ENABLE_WRITES: "true",
      FLYDB_PASSWORD: pgPassword,
    });
    try {
      const plan = await callTool(client, "loop", setup, "flydb_plan_migrate") as {
        plan?: {id?: string; migrationCount?: number; statementCount?: number}};
      expect(plan.plan?.migrationCount).toBe(1);
      expect(plan.plan?.id).toMatch(/^[0-9a-f]{64}$/);

      const migrate = await callTool(client, "loop", setup, "flydb_migrate") as {
        executed?: string[]; targetVersionReached?: string};
      expect(migrate.executed).toEqual(["V1__init.sql"]);
      expect(migrate.targetVersionReached).toBe("1");

      const info = await callTool(client, "loop", setup, "flydb_info") as {current?: string};
      expect(info.current).toBe("1");

      const validate = await callTool(client, "loop", setup, "flydb_validate") as {
        status?: string};
      expect(validate.status).toBe("success");

      const replan = await callTool(client, "loop", setup, "flydb_plan_migrate") as {
        plan?: {migrationCount?: number}};
      expect(replan.plan?.migrationCount).toBe(0);
    } finally {
      await client.close();
    }
  });

  it("baseline fixture：存量库写入基准版本", async () => {
    const setup = projectSetup("mcp_baseline", {}, ["flydb.baseline-version=20260801"]);
    const client = await connectClient({
      FLYDB_CLI: e2eCli,
      FLYDB_MCP_ENABLE_WRITES: "true",
      FLYDB_PASSWORD: pgPassword,
    });
    try {
      const baseline = await callTool(client, "baseline", setup, "flydb_baseline") as {
        baselineVersion?: string};
      expect(baseline.baselineVersion).toBe("20260801");
      const info = await callTool(client, "baseline", setup, "flydb_info") as {
        current?: string; migrations?: Array<{state?: string}>};
      expect(info.current).toBe("20260801");
      expect(info.migrations?.[0]?.state).toBe("BASELINE");
    } finally {
      await client.close();
    }
  });

  it("repair fixture：篡改 checksum 后修复", async () => {
    const setup = projectSetup("mcp_repair", V1);
    const client = await connectClient({
      FLYDB_CLI: e2eCli,
      FLYDB_MCP_ENABLE_WRITES: "true",
      FLYDB_PASSWORD: pgPassword,
    });
    try {
      await callTool(client, "repair", setup, "flydb_migrate");
      sh(`docker exec ${containerName} psql -U postgres -d mcp_repair -c `
          + `\"UPDATE mcp_repair_history SET checksum = 999 WHERE version = '1'\"`);
      const broken = await callTool(client, "repair", setup, "flydb_validate") as {
        status?: string; error?: {code?: string}};
      expect(broken.status).toBe("error");

      const repair = await callTool(client, "repair", setup, "flydb_repair") as {
        alignedChecksums?: string[]};
      expect(repair.alignedChecksums).toHaveLength(1);
      const validate = await callTool(client, "repair", setup, "flydb_validate") as {
        status?: string};
      expect(validate.status).toBe("success");
    } finally {
      await client.close();
    }
  });

  it("undo fixture：plan_undo → undo 回退到空库", async () => {
    const setup = projectSetup("mcp_undo", V1);
    const client = await connectClient({
      FLYDB_CLI: e2eCli,
      FLYDB_MCP_ENABLE_WRITES: "true",
      FLYDB_PASSWORD: pgPassword,
    });
    try {
      await callTool(client, "undo", setup, "flydb_migrate");
      const plan = await callTool(client, "undo", setup, "flydb_plan_undo") as {
        plan?: {direction?: string; migrationCount?: number}};
      expect(plan.plan?.direction).toBe("undo");
      expect(plan.plan?.migrationCount).toBe(1);

      const undo = await callTool(client, "undo", setup, "flydb_undo") as {
        undoneVersion?: string};
      expect(undo.undoneVersion).toBe("1");
      const info = await callTool(client, "undo", setup, "flydb_info") as {current?: string};
      expect(info.current).toBeNull();
    } finally {
      await client.close();
    }
  });
});
