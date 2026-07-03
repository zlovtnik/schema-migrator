import { describe, expect, it } from "vitest";
import { breadcrumbTarget } from "./breadcrumbs";

describe("breadcrumbTarget", () => {
  it("adds the selected target to target-aware breadcrumb links", () => {
    expect(breadcrumbTarget("/runs", "target-1")).toBe("/runs?target=target-1");
  });

  it("preserves existing search params when adding the selected target", () => {
    expect(breadcrumbTarget("/runs?status=failed", "target-1")).toBe("/runs?status=failed&target=target-1");
  });
});
