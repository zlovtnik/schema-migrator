import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const srcDir = join(process.cwd(), "src");
const dataTablePath = join(srcDir, "components/ui/DataTable.tsx");

const tsxFiles = (directory: string): string[] =>
  readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    return statSync(path).isDirectory() ? tsxFiles(path) : entry.endsWith(".tsx") ? [path] : [];
  });

describe("DataTable architecture", () => {
  it("keeps the only raw table element inside DataTable", () => {
    const offenders = tsxFiles(srcDir)
      .filter((path) => path !== dataTablePath)
      .filter((path) => readFileSync(path, "utf8").includes("<table"))
      .map((path) => relative(srcDir, path));

    expect(offenders).toEqual([]);
  });
});
