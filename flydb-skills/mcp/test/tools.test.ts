import { describe, expect, it } from "vitest";
import { DEFAULT_TOOLS, FLYDB_TOOLS, WRITE_TOOLS, writesEnabled } from "../src/tools.js";

const names = (tools: {name: string}[]): string[] => tools.map((tool) => tool.name);

describe("v1 工具集", () => {
  it("九个工具、名称与决策 §4 一一对应", () => {
    expect(names([...FLYDB_TOOLS])).toEqual([
      "flydb_version", "flydb_info", "flydb_validate",
      "flydb_plan_migrate", "flydb_plan_undo",
      "flydb_migrate", "flydb_baseline", "flydb_repair", "flydb_undo",
    ]);
    expect(names([...DEFAULT_TOOLS])).toEqual([
      "flydb_version", "flydb_info", "flydb_validate",
      "flydb_plan_migrate", "flydb_plan_undo",
    ]);
    expect(names([...WRITE_TOOLS])).toEqual([
      "flydb_migrate", "flydb_baseline", "flydb_repair", "flydb_undo",
    ]);
  });

  it("永不包含 clean/init/execute_sql 语义", () => {
    for (const tool of FLYDB_TOOLS) {
      expect(tool.name).not.toMatch(/clean|init|execute_sql|sql$/i);
      expect(tool.cliAction.join(" ")).not.toMatch(/clean|init/);
    }
  });

  it("数据库工具固定动作与 --driver-download never", () => {
    const cases: Array<[string, string[]]> = [
      ["flydb_info", ["info"]],
      ["flydb_validate", ["validate"]],
      ["flydb_plan_migrate", ["--dry-run", "migrate"]],
      ["flydb_plan_undo", ["--dry-run", "undo"]],
      ["flydb_migrate", ["migrate"]],
      ["flydb_baseline", ["baseline"]],
      ["flydb_repair", ["repair"]],
      ["flydb_undo", ["undo"]],
    ];
    for (const [name, action] of cases) {
      const tool = FLYDB_TOOLS.find((candidate) => candidate.name === name)!;
      expect([...tool.cliAction]).toEqual(action);
      const build = tool.buildArgs({
        workingDirectory: "/work/dir",
        configPath: "/work/dir/flydb.conf",
      });
      expect(build.ok).toBe(true);
      if (build.ok) {
        expect(build.args).toEqual(
            ["--json", "-c", "/work/dir/flydb.conf", "--driver-download", "never", ...action]);
        expect(build.cwd).toBe("/work/dir");
      }
    }
  });

  it("flydb_version 不接收任何输入且不连库", () => {
    const tool = FLYDB_TOOLS.find((candidate) => candidate.name === "flydb_version")!;
    const build = tool.buildArgs({});
    expect(build.ok).toBe(true);
    if (build.ok) expect(build.args).toEqual(["--json", "version"]);
  });

  it("输入白名单：拒绝多余字段、相对路径、非字符串", () => {
    const tool = FLYDB_TOOLS.find((candidate) => candidate.name === "flydb_migrate")!;
    expect(tool.buildArgs({workingDirectory: "relative", configPath: "/abs/flydb.conf"}).ok)
        .toBe(false);
    expect(tool.buildArgs({workingDirectory: "/abs", configPath: "rel.conf"}).ok).toBe(false);
    expect(tool.buildArgs({workingDirectory: "/abs", configPath: "/c.conf", url: "jdbc:x"}).ok)
        .toBe(false);
    expect(tool.buildArgs({workingDirectory: "/abs", configPath: "/c.conf", password: "p"}).ok)
        .toBe(false);
    expect(tool.buildArgs({workingDirectory: 1, configPath: "/c.conf"}).ok).toBe(false);
    expect(tool.buildArgs("nope").ok).toBe(false);
    expect(tool.inputSchema["additionalProperties"]).toBe(false);
  });

  it("annotations 按决策 §4.2 标注", () => {
    const byName = new Map(FLYDB_TOOLS.map((tool) => [tool.name, tool.annotations]));
    expect(byName.get("flydb_version")).toMatchObject({readOnlyHint: true, destructiveHint: false});
    expect(byName.get("flydb_plan_migrate")).toMatchObject({readOnlyHint: true});
    for (const name of ["flydb_migrate", "flydb_repair", "flydb_undo"]) {
      expect(byName.get(name)).toMatchObject({readOnlyHint: false, destructiveHint: true});
    }
    expect(byName.get("flydb_baseline"))
        .toMatchObject({readOnlyHint: false, destructiveHint: false});
  });

  it("每个工具声明开放 outputSchema 且覆盖信封核心字段", () => {
    for (const tool of FLYDB_TOOLS) {
      const schema = tool.outputSchema as Record<string, unknown>;
      const properties = schema["properties"] as Record<string, unknown> | undefined;
      expect(schema["required"]).toEqual(["protocolVersion", "command", "status", "exitCode"]);
      expect(properties?.["error"]).toBeDefined();
      // protocolVersion=1 只加字段：schema 不得 additionalProperties:false
      expect(schema["additionalProperties"]).toBeUndefined();
    }
  });

  it("writesEnabled fail closed：仅字面 true（大小写不敏感）开启", () => {
    expect(writesEnabled({})).toBe(false);
    expect(writesEnabled({FLYDB_MCP_ENABLE_WRITES: "false"})).toBe(false);
    expect(writesEnabled({FLYDB_MCP_ENABLE_WRITES: "1"})).toBe(false);
    expect(writesEnabled({FLYDB_MCP_ENABLE_WRITES: "yes"})).toBe(false);
    expect(writesEnabled({FLYDB_MCP_ENABLE_WRITES: ""})).toBe(false);
    expect(writesEnabled({FLYDB_MCP_ENABLE_WRITES: "true"})).toBe(true);
    expect(writesEnabled({FLYDB_MCP_ENABLE_WRITES: "TRUE"})).toBe(true);
  });
});
