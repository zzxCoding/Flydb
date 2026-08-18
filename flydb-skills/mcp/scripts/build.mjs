// 单文件 bundle：版本号从 package.json 注入，保证与 npm SemVer 单一来源。
import { build } from "esbuild";
import { readFileSync } from "node:fs";

const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));

await build({
  entryPoints: ["src/server.ts"],
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node20",
  outfile: "dist/server.mjs",
  banner: {js: "#!/usr/bin/env node"},
  define: {ADAPTER_VERSION: JSON.stringify(pkg.version)},
  minify: true,
});
