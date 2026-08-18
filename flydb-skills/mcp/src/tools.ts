/**
 * v1 工具集（技术决策 §4）：只映射 Flydb 领域命令，永不含 clean/init，
 * 不提供 execute_sql、任意命令名或 rawArgs。数据库工具必须接收显式绝对
 * `workingDirectory` 与 `configPath`，固定追加 `--driver-download never`。
 * 除这两个白名单字段外不接受任何其他输入；密码、JDBC URL、环境变量一律不进 tool 输入。
 */

import { isAbsolute } from "node:path";

export interface JsonSchema {
  readonly [key: string]: unknown;
}

export interface ToolInput {
  workingDirectory?: string;
  configPath?: string;
}

export interface ToolAnnotations {
  readonly title: string;
  readonly readOnlyHint: boolean;
  readonly destructiveHint: boolean;
  readonly idempotentHint: boolean;
  readonly openToWorldHint: boolean;
}

export type InputBuildResult =
    | { ok: true; args: string[]; cwd: string }
    | { ok: false; error: string };

export interface FlydbTool {
  readonly name: string;
  readonly description: string;
  /** 是否写入工具：默认不注册，FLYDB_MCP_ENABLE_WRITES=true 才注册。 */
  readonly writes: boolean;
  /** CLI 动作固定，输入只填充工作目录与配置文件。 */
  readonly cliAction: readonly string[];
  readonly annotations: ToolAnnotations;
  readonly inputSchema: JsonSchema;
  readonly outputSchema: JsonSchema;
  buildArgs(input: unknown): InputBuildResult;
}

const DATABASE_INPUT_SCHEMA: JsonSchema = {
  type: "object",
  additionalProperties: false,
  required: ["workingDirectory", "configPath"],
  properties: {
    workingDirectory: {
      type: "string",
      description: "项目工作目录（绝对路径）。CLI 以该目录解析相对 locations 等配置。",
    },
    configPath: {
      type: "string",
      description: "flydb 配置文件（绝对路径）。Adapter 以 -c 显式传入，不做隐式配置搜索。",
    },
  },
};

const VERSION_INPUT_SCHEMA: JsonSchema = {
  type: "object",
  additionalProperties: false,
  properties: {},
};

function envelopeOutputSchema(payload: Record<string, unknown>): JsonSchema {
  return {
    type: "object",
    required: ["protocolVersion", "command", "status", "exitCode"],
    properties: {
      protocolVersion: {type: "integer", const: 1},
      command: {type: ["string", "null"]},
      status: {type: "string", enum: ["success", "error"]},
      exitCode: {type: "integer"},
      ...payload,
      error: {
        type: ["object", "null"],
        properties: {
          code: {type: ["string", "null"]},
          detail: {type: ["string", "null"]},
          problems: {
            type: "array",
            items: {
              type: "object",
              properties: {code: {type: "string"}, detail: {type: "string"}},
            },
          },
        },
      },
    },
  };
}

const STRING_ARRAY = {type: "array", items: {type: "string"}};

const PLAN_PAYLOAD_SCHEMA: Record<string, unknown> = {
  dryRun: {type: "boolean", const: true},
  plan: {
    type: "object",
    properties: {
      algorithm: {type: "string"},
      direction: {type: "string", enum: ["migrate", "undo"]},
      id: {type: "string"},
      targetVersion: {type: ["string", "null"]},
      migrationCount: {type: "integer"},
      statementCount: {type: "integer"},
    },
  },
  migrations: {
    type: "array",
    items: {
      type: "object",
      properties: {
        script: {type: "string"},
        type: {type: "string"},
        version: {type: ["string", "null"]},
        description: {type: ["string", "null"]},
        checksum: {type: ["integer", "null"]},
        statementCount: {type: "integer"},
        statements: {
          type: "array",
          items: {
            type: "object",
            properties: {lineNumber: {type: "integer"}, sql: {type: "string"}},
          },
        },
      },
    },
  },
};

function dbTool(config: {
  name: string;
  description: string;
  writes: boolean;
  cliAction: readonly string[];
  annotations: Omit<ToolAnnotations, "title"> & {title: string};
  outputSchema: JsonSchema;
}): FlydbTool {
  return {
    name: config.name,
    description: config.description,
    writes: config.writes,
    cliAction: config.cliAction,
    annotations: config.annotations,
    inputSchema: DATABASE_INPUT_SCHEMA,
    outputSchema: config.outputSchema,
    buildArgs(input: unknown): InputBuildResult {
      const error = validateDatabaseInput(input);
      if (error !== null) return {ok: false, error};
      const toolInput = input as ToolInput;
      const workingDirectory = toolInput["workingDirectory"] as string;
      const configPath = toolInput["configPath"] as string;
      return {
        ok: true,
        cwd: workingDirectory,
        args: ["--json", "-c", configPath, "--driver-download", "never", ...config.cliAction],
      };
    },
  };
}

function validateDatabaseInput(input: unknown): string | null {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    return "输入必须是对象";
  }
  const record = input as Record<string, unknown>;
  const allowed = new Set(["workingDirectory", "configPath"]);
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) return `不接受字段 ${key}；只允许 workingDirectory 与 configPath`;
  }
  for (const key of ["workingDirectory", "configPath"]) {
    const value = record[key];
    if (typeof value !== "string" || value.length === 0) {
      return `${key} 必须是非空字符串`;
    }
    if (!isAbsolute(value)) {
      return `${key} 必须是绝对路径: ${value}`;
    }
  }
  return null;
}

export const FLYDB_TOOLS: readonly FlydbTool[] = [
  {
    name: "flydb_version",
    description: "查询 flydb CLI 自身版本，不连接数据库。用于确认 Adapter 定位的 CLI 可用。",
    writes: false,
    cliAction: ["version"],
    annotations: {
      title: "Flydb 版本",
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openToWorldHint: false,
    },
    inputSchema: VERSION_INPUT_SCHEMA,
    outputSchema: envelopeOutputSchema({
      version: {type: ["string", "null"]},
    }),
    buildArgs(input: unknown): InputBuildResult {
      if (typeof input !== "object" || input === null || Array.isArray(input)
          || Object.keys(input as Record<string, unknown>).length > 0) {
        return {ok: false, error: "flydb_version 不接受任何输入字段"};
      }
      return {ok: true, args: ["--json", "version"], cwd: process.cwd()};
    },
  },
  dbTool({
    name: "flydb_info",
    description: "查看迁移状态总表（只读）：数据库、当前版本与逐个迁移的状态。",
    writes: false,
    cliAction: ["info"],
    annotations: {
      title: "Flydb 迁移状态",
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema({
      databaseName: {type: ["string", "null"]},
      url: {type: ["string", "null"], description: "已脱敏"},
      historyTable: {type: ["string", "null"]},
      current: {type: ["string", "null"]},
      migrations: {
        type: "array",
        items: {
          type: "object",
          properties: {
            version: {type: ["string", "null"]},
            description: {type: ["string", "null"]},
            type: {type: "string"},
            script: {type: ["string", "null"]},
            checksum: {type: ["integer", "null"]},
            installedOn: {type: ["string", "null"]},
            executionTimeMillis: {type: ["integer", "null"]},
            state: {type: "string"},
          },
        },
      },
    }),
  }),
  dbTool({
    name: "flydb_validate",
    description: "校验本地迁移脚本与历史记录一致性（只读）。失败信封的 problems 逐条列出问题。",
    writes: false,
    cliAction: ["validate"],
    annotations: {
      title: "Flydb 校验",
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema({}),
  }),
  dbTool({
    name: "flydb_plan_migrate",
    description: "预演将要执行的迁移（只读，不写库）：返回 Plan Artifact（plan.id 摘要与逐语句 SQL）。"
        + "执行写入前必须先用本工具核对计划并获得用户明确授权。",
    writes: false,
    cliAction: ["--dry-run", "migrate"],
    annotations: {
      title: "Flydb 迁移预演",
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema(PLAN_PAYLOAD_SCHEMA),
  }),
  dbTool({
    name: "flydb_plan_undo",
    description: "预演将要撤销的迁移（只读，不写库）：返回 Plan Artifact 与回退 SQL。"
        + "执行 undo 前必须先用本工具核对回退计划并获得用户明确授权。",
    writes: false,
    cliAction: ["--dry-run", "undo"],
    annotations: {
      title: "Flydb 撤销预演",
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema(PLAN_PAYLOAD_SCHEMA),
  }),
  dbTool({
    name: "flydb_migrate",
    description: "执行待应用的迁移（写库，持迁移锁）。调用前必须已用 flydb_plan_migrate 核对计划"
        + "并获得用户明确授权。迁移 SQL 可含破坏性语句。",
    writes: true,
    cliAction: ["migrate"],
    annotations: {
      title: "Flydb 执行迁移",
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema({
      executed: STRING_ARRAY,
      targetVersionReached: {type: ["string", "null"]},
      totalExecutionTimeMillis: {type: ["integer", "null"]},
      warnings: STRING_ARRAY,
    }),
  }),
  dbTool({
    name: "flydb_baseline",
    description: "为存量库写入基准版本（写历史表，仅追加一条记录）。baselineVersion 取自配置文件。",
    writes: true,
    cliAction: ["baseline"],
    annotations: {
      title: "Flydb 基线",
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: false,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema({
      baselineVersion: {type: ["string", "null"]},
    }),
  }),
  dbTool({
    name: "flydb_repair",
    description: "清除失败记录并对齐校验和（修改历史表）。用于修复校验失败后的历史表状态。",
    writes: true,
    cliAction: ["repair"],
    annotations: {
      title: "Flydb 修复",
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema({
      removedFailedRecords: STRING_ARRAY,
      alignedChecksums: STRING_ARRAY,
    }),
  }),
  dbTool({
    name: "flydb_undo",
    description: "撤销最近一次版本化迁移（持锁执行回退 SQL）。调用前必须已用 flydb_plan_undo"
        + " 核对回退计划并获得用户明确授权。",
    writes: true,
    cliAction: ["undo"],
    annotations: {
      title: "Flydb 撤销迁移",
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openToWorldHint: false,
    },
    outputSchema: envelopeOutputSchema({
      undoneVersion: {type: ["string", "null"]},
      executionTimeMillis: {type: ["integer", "null"]},
    }),
  }),
];

export const DEFAULT_TOOLS: readonly FlydbTool[] = FLYDB_TOOLS.filter((tool) => !tool.writes);
export const WRITE_TOOLS: readonly FlydbTool[] = FLYDB_TOOLS.filter((tool) => tool.writes);

/** fail closed：仅不区分大小写的字面值 true 开启写入，缺失或非法一律关闭。 */
export function writesEnabled(env: NodeJS.ProcessEnv): boolean {
  const raw = env["FLYDB_MCP_ENABLE_WRITES"];
  if (raw === undefined) return false;
  return raw.toLowerCase() === "true";
}
