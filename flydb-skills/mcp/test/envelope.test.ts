import { describe, expect, it } from "vitest";
import { EnvelopeFormatError, parseEnvelope } from "../src/envelope.js";

function envelopeError(action: () => void): EnvelopeFormatError {
  try {
    action();
  } catch (error) {
    if (error instanceof EnvelopeFormatError) return error;
    throw error;
  }
  throw new Error("expected EnvelopeFormatError");
}

describe("parseEnvelope", () => {
  it("接受合法成功信封并保留未知字段", () => {
    const envelope = parseEnvelope(
        '{"protocolVersion":1,"command":"migrate","status":"success","exitCode":0,"executed":["V1__a.sql"],"futureField":{"x":1}}\n');
    expect(envelope.status).toBe("success");
    expect(envelope.command).toBe("migrate");
    expect(envelope["executed"]).toEqual(["V1__a.sql"]);
    expect(envelope["futureField"]).toEqual({x: 1});
  });

  it("接受合法错误信封", () => {
    const envelope = parseEnvelope(
        '{"protocolVersion":1,"command":"migrate","status":"error","exitCode":4,'
        + '"error":{"code":"FLYDB-4002","detail":"必须提供 flydb.url","problems":[]}}');
    expect(envelope.status).toBe("error");
    expect(envelope.error?.code).toBe("FLYDB-4002");
    expect(envelope.error?.problems).toEqual([]);
  });

  it("拒绝空 stdout", () => {
    expect(envelopeError(() => parseEnvelope("   \n")).adapterCode).toBe("FLYDB_MCP-0004");
  });

  it("拒绝非 JSON 与多文档 stdout", () => {
    expect(envelopeError(() => parseEnvelope("not json")).adapterCode).toBe("FLYDB_MCP-0004");
    expect(envelopeError(() => parseEnvelope('{"a":1} {"b":2}')).adapterCode)
        .toBe("FLYDB_MCP-0004");
  });

  it("拒绝非对象 JSON", () => {
    expect(envelopeError(() => parseEnvelope("[1,2]")).adapterCode).toBe("FLYDB_MCP-0004");
    expect(envelopeError(() => parseEnvelope("42")).adapterCode).toBe("FLYDB_MCP-0004");
  });

  it("拒绝 protocolVersion 不兼容", () => {
    expect(envelopeError(() => parseEnvelope(
        '{"protocolVersion":2,"command":"migrate","status":"success","exitCode":0}')).adapterCode)
        .toBe("FLYDB_MCP-0005");
  });

  it("拒绝非法 status 与 exitCode", () => {
    expect(envelopeError(() => parseEnvelope(
        '{"protocolVersion":1,"command":"migrate","status":"ok","exitCode":0}')).adapterCode)
        .toBe("FLYDB_MCP-0007");
    expect(envelopeError(() => parseEnvelope(
        '{"protocolVersion":1,"command":"migrate","status":"success","exitCode":"0"}')).adapterCode)
        .toBe("FLYDB_MCP-0007");
  });

  it("拒绝错误信封缺失 error 对象", () => {
    expect(envelopeError(() => parseEnvelope(
        '{"protocolVersion":1,"command":"migrate","status":"error","exitCode":4}')).adapterCode)
        .toBe("FLYDB_MCP-0007");
  });

  it("command 允许 null（解析期失败）", () => {
    const envelope = parseEnvelope(
        '{"protocolVersion":1,"command":null,"status":"error","exitCode":4,'
        + '"error":{"code":null,"detail":"boom","problems":[]}}');
    expect(envelope.command).toBeNull();
  });
});
