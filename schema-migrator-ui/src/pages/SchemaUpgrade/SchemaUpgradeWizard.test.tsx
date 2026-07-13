import { describe, expect, it } from "vitest";
import type { SchemaObjectListItem } from "../../types";
import { groupFiles } from "./SchemaUpgradeWizard";

const objects: SchemaObjectListItem[] = [
  {
    folder: "tables",
    path: "tables/001_accounts.sql",
    object_type: "table",
    status: "defined",
    source_file: "tables/001_accounts.sql"
  },
  {
    folder: "tables",
    path: "tables/001_accounts.sql",
    object_type: "index",
    status: "defined",
    source_file: "tables/001_accounts.sql"
  },
  {
    folder: "tables",
    path: "tables/002_devices.sql",
    object_type: "table",
    status: "defined",
    source_file: "tables/002_devices.sql"
  }
];

describe("groupFiles", () => {
  it("creates one independently selectable row per source file", () => {
    const groups = groupFiles(objects);

    expect(groups).toHaveLength(1);
    expect(groups[0]?.files).toHaveLength(2);
    expect(groups[0]?.files.map((file) => file.sourceFile)).toEqual([
      "tables/001_accounts.sql",
      "tables/002_devices.sql"
    ]);
    expect(groups[0]?.files[0]?.objectTypes).toEqual(["table", "index"]);
  });
});
