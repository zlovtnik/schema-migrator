import { describe, expect, it } from "vitest";
import type { AuditEvent, Target } from "../types";
import { formatActivityAction, formatActivityActor, formatActivityEntity } from "./activityPresentation";

const event = (overrides: Partial<AuditEvent> = {}): AuditEvent =>
  ({
    id: "event-1",
    actor: "unknown",
    action: "target.update",
    entity_type: "target",
    entity_id: "f19a1d11-f9a9-4e7a-a4bf-22dc74d2681c",
    at: "2026-07-13T12:00:00Z",
    ...overrides
  }) as AuditEvent;

describe("activity presentation", () => {
  it("maps audit codes to plain-language actions", () => {
    expect(formatActivityAction("run.resolve")).toBe("Run failure resolved");
    expect(formatActivityAction("drift.run")).toBe("Drift check started");
    expect(formatActivityAction("target.update")).toBe("Target updated");
    expect(formatActivityAction("custom.background_task")).toBe("Background task");
  });

  it("replaces unknown and opaque actors with useful labels", () => {
    expect(formatActivityActor(event())).toBe("System");
    expect(formatActivityActor(event({ actor: "scheduler" }))).toBe("Scheduled");
    expect(formatActivityActor(event({ actor: "a01a1d11-f9a9-4e7a-a4bf-22dc74d2681c" }))).toBe("Authenticated user");
  });

  it("resolves entity identifiers to display names", () => {
    const target = {
      id: "f19a1d11-f9a9-4e7a-a4bf-22dc74d2681c",
      label: "Orders production"
    } as Target;

    expect(formatActivityEntity(event(), { targets: [target] })).toBe("Orders production");
    expect(formatActivityEntity(event({ entity_type: "snapshot", entity_id: "snapshot-1" }))).toBe("Snapshot");
  });
});
