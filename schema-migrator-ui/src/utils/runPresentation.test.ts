import { describe, expect, it } from "vitest";
import type { AuditEvent, Patch, Run } from "../types";
import {
  buildRunTrend,
  formatRunSource,
  recentlyAbortedRuns,
  runFailureReason,
  runOutcomeFallback
} from "./runPresentation";

const run = (overrides: Partial<Run> = {}): Run =>
  ({
    id: "run-1",
    target_id: "target-1",
    patch_id: "patch-1",
    status: "completed",
    scripts: [],
    started_at: "2026-07-13T12:00:00Z",
    triggered_by: "operator",
    ...overrides
  }) as Run;

describe("run presentation", () => {
  it("uses trigger intent instead of patch identifiers", () => {
    const driftEvent = {
      id: "event-1",
      actor: "operator",
      action: "drift.run",
      entity_type: "run",
      entity_id: "run-1",
      at: "2026-07-13T12:00:00Z"
    } as AuditEvent;
    const rollbackPatch = { id: "patch-1", source_snapshot_id: "snapshot-1" } as Patch;

    expect(formatRunSource(run(), undefined, [driftEvent])).toBe("Drift check");
    expect(formatRunSource(run(), rollbackPatch, [driftEvent])).toBe("Snapshot rollback");
    expect(formatRunSource(run())).toBe("Schema apply");
  });

  it("surfaces script errors and a useful aborted reason", () => {
    expect(runFailureReason(run({ status: "aborted" }))).toBe("Stopped before completion");
    expect(
      runFailureReason(
        run({
          status: "failed",
          scripts: [
            {
              script_id: "script-1",
              filename: "tables/001.sql",
              order: 1,
              status: "failed",
              error: { db_code: "XX000", message: "Lock timed out" }
            }
          ]
        })
      )
    ).toBe("Lock timed out");
  });

  it("uses status-specific outcome fallbacks", () => {
    expect(runOutcomeFallback("pending")).toBe("Pending");
    expect(runOutcomeFallback("running")).toBe("Running");
    expect(runOutcomeFallback("completed")).toBe("Completed without interruption");
  });

  it("builds a seven-day completed and interrupted trend", () => {
    const now = Date.parse("2026-07-13T12:00:00Z");
    const trend = buildRunTrend(
      [
        run(),
        run({ id: "run-2", status: "failed", started_at: "2026-07-12T12:00:00Z" as Run["started_at"] }),
        run({ id: "run-3", status: "aborted", started_at: "2026-07-07T12:00:00Z" as Run["started_at"] })
      ],
      now
    );

    expect(trend).toHaveLength(7);
    expect(trend.at(-1)).toMatchObject({ completed: 1, interrupted: 0 });
    expect(trend.at(-2)).toMatchObject({ completed: 0, interrupted: 1 });
    expect(
      recentlyAbortedRuns([run({ status: "aborted", started_at: "2026-07-07T12:00:00Z" as Run["started_at"] })], now)
    ).toHaveLength(1);
  });
});
