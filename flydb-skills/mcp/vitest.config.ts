import { readFileSync } from "node:fs";
import { defineConfig } from "vitest/config";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));

export default defineConfig({
  define: {ADAPTER_VERSION: JSON.stringify(pkg.version)},
  test: {
    include: ["test/**/*.test.ts"],
  },
});
