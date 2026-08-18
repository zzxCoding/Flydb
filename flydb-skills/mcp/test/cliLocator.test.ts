import { mkdtempSync, chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { CliLocatorError, resolveCliExecutable } from "../src/cliLocator.js";

const created: string[] = [];

function tempDir(prefix: string): string {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  created.push(dir);
  return dir;
}

function makeExecutable(file: string, content = "#!/bin/sh\nexit 0\n"): string {
  writeFileSync(file, content, {mode: 0o755});
  chmodSync(file, 0o755);
  return file;
}

afterEach(() => {
  created.length = 0;
});

describe("resolveCliExecutable", () => {
  it("FLYDB_CLI 绝对路径优先", () => {
    const dir = tempDir("flydb-locator-");
    const executable = makeExecutable(join(dir, "flydb"));
    expect(resolveCliExecutable({FLYDB_CLI: executable}, "darwin")).toBe(executable);
  });

  it("FLYDB_CLI 相对路径直接报错", () => {
    expect(() => resolveCliExecutable({FLYDB_CLI: "bin/flydb"}, "darwin"))
        .toThrow(CliLocatorError);
  });

  it("FLYDB_CLI 指向不存在的文件直接报错", () => {
    expect(() => resolveCliExecutable({FLYDB_CLI: "/nonexistent/flydb"}, "darwin"))
        .toThrow(CliLocatorError);
  });

  it("FLYDB_HOME 定位 bin/flydb，Windows 用 flydb.bat", () => {
    const home = tempDir("flydb-home-");
    mkdirSync(join(home, "bin"));
    const executable = makeExecutable(join(home, "bin", "flydb"));
    const bat = makeExecutable(join(home, "bin", "flydb.bat"), "@echo off\r\n");
    expect(resolveCliExecutable({FLYDB_HOME: home}, "darwin")).toBe(executable);
    expect(resolveCliExecutable({FLYDB_HOME: home}, "win32")).toBe(bat);
  });

  it("PATH 中定位 flydb", () => {
    const dir = tempDir("flydb-path-");
    const executable = makeExecutable(join(dir, "flydb"));
    expect(resolveCliExecutable({PATH: dir}, "darwin")).toBe(executable);
  });

  it("PATH 中无 flydb 时报错并给出设置指引", () => {
    const empty = tempDir("flydb-empty-");
    expect(() => resolveCliExecutable({PATH: empty}, "darwin"))
        .toThrow(/FLYDB_CLI|FLYDB_HOME|PATH/);
  });
});
