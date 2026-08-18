/**
 * flydb CLI 可执行文件定位（技术决策 §3.3，顺序固定）：
 * 1. `FLYDB_CLI` 指向的绝对可执行文件；
 * 2. `FLYDB_HOME/bin/flydb`（Windows 为 `FLYDB_HOME/bin/flydb.bat`）；
 * 3. `PATH` 中的 `flydb`。
 */

import { accessSync, constants as fsConstants, existsSync } from "node:fs";
import { delimiter, isAbsolute, join, sep } from "node:path";

export class CliLocatorError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CliLocatorError";
  }
}

function isExecutable(file: string): boolean {
  try {
    accessSync(file, fsConstants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function findOnPath(command: string, env: NodeJS.ProcessEnv,
                    platform: NodeJS.Platform): string | null {
  const pathValue = env["PATH"];
  if (!pathValue) return null;
  const candidates = platform === "win32"
      ? [`${command}.bat`, `${command}.cmd`, `${command}.exe`]
      : [command];
  for (const directory of pathValue.split(delimiter)) {
    if (directory.length === 0) continue;
    for (const candidate of candidates) {
      const file = join(directory, candidate);
      if (existsSync(file) && isExecutable(file)) return file;
    }
  }
  return null;
}

/**
 * 解析 flydb CLI 可执行文件路径；找不到时抛出 CliLocatorError。
 * `platform` 参数化以便跨平台行为可测试。
 */
export function resolveCliExecutable(env: NodeJS.ProcessEnv,
                                     platform: NodeJS.Platform = process.platform): string {
  const explicit = env["FLYDB_CLI"];
  if (explicit !== undefined && explicit.trim().length > 0) {
    const value = explicit.trim();
    if (!isAbsolute(value)) {
      throw new CliLocatorError(`FLYDB_CLI 必须指向绝对路径: ${value}`);
    }
    if (!existsSync(value)) {
      throw new CliLocatorError(`FLYDB_CLI 指向的文件不存在: ${value}`);
    }
    return value;
  }
  const home = env["FLYDB_HOME"];
  if (home !== undefined && home.trim().length > 0) {
    const executable = join(home.trim(), "bin",
        platform === "win32" ? "flydb.bat" : "flydb");
    if (existsSync(executable) && isExecutable(executable)) return executable;
  }
  const onPath = findOnPath("flydb", env, platform);
  if (onPath !== null) return onPath;
  throw new CliLocatorError(
      "未找到 flydb CLI：请设置 FLYDB_CLI 指向发行包 bin/flydb 的绝对路径，"
      + "或设置 FLYDB_HOME 指向发行包根目录，或将 bin 目录加入 PATH");
}

/** 供测试与诊断使用：目录分隔符（避免在输出中暴露平台差异细节）。 */
export const pathSeparator = sep;
