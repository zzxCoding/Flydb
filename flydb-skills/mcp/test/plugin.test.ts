import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/**
 * Agent Plugins 1.0.0 插件包结构校验（技术决策 §7.2 的离线部分）：
 * plugin.json 与 mcp.json 的 $schema 版本必须对齐（1.0.0），
 * mcp 入口必须是插件根目录内的单命令 stdio 配置。
 */

const mcpDir = dirname(fileURLToPath(import.meta.url));
const pluginRoot = resolve(mcpDir, "../..");

function readJson(relative: string): Record<string, unknown> {
  const text = readFileSync(join(pluginRoot, relative), "utf8");
  return JSON.parse(text) as Record<string, unknown>;
}

describe("Agent Plugins 1.0.0 结构", () => {
  it("plugin.json 与 mcp.json 的 $schema 版本对齐", () => {
    const plugin = readJson("plugin.json");
    const mcp = readJson("mcp.json");
    const pluginSchema = plugin["$schema"] as string;
    const mcpSchema = mcp["$schema"] as string;
    expect(pluginSchema).toMatch(/\/schemas\/1\.0\.0\/plugin\.schema\.json$/);
    expect(mcpSchema).toMatch(/\/schemas\/1\.0\.0\/mcp\.schema\.json$/);
  });

  it("mcp.json 是单命令 stdio 配置且入口位于插件根目录内", () => {
    const mcp = readJson("mcp.json");
    const servers = mcp["mcpServers"] as Record<string, Record<string, unknown>>;
    expect(Object.keys(servers)).toEqual(["flydb"]);
    const server = servers["flydb"]!;
    expect(server["type"]).toBe("stdio");
    expect(typeof server["command"]).toBe("string");
    expect((server["command"] as string).trim()).toBe("node");
    expect((server["command"] as string)).not.toMatch(/\s/);
    const args = server["args"] as string[];
    const entry = args.find((value) => value.includes("${PLUGIN_ROOT}"));
    expect(entry).toBeDefined();
    expect(entry).toBe("${PLUGIN_ROOT}/mcp/dist/server.mjs");
    const relative = entry!.replace("${PLUGIN_ROOT}", "");
    expect(resolve(pluginRoot, `.${relative}`)).toBe(
        resolve(pluginRoot, `mcp/dist/server.mjs`));
  });

  it("插件入口源码存在于插件根目录内", () => {
    expect(existsSync(join(pluginRoot, "mcp/src/server.ts"))).toBe(true);
    expect(existsSync(join(pluginRoot, "plugin.json"))).toBe(true);
    expect(existsSync(join(pluginRoot, "mcp.json"))).toBe(true);
  });

  it("npm 包声明 CLI 兼容范围与 protocolVersion", () => {
    const pkg = readJson("mcp/package.json");
    const flydb = pkg["flydb"] as Record<string, unknown>;
    expect(flydb["cli"]).toBe(">=0.3.0 <0.4.0");
    expect(flydb["protocolVersion"]).toBe(1);
    expect(pkg["name"]).toBe("flydb-mcp");
  });
});
