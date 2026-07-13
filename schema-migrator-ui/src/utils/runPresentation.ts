import type { AuditEvent, Patch, Run } from "../types";

export type RunSourceLabel = "Drift check" | "Schema apply" | "Snapshot rollback";

export interface RunTrendDay {
  key: string;
  label: string;
  completed: number;
  interrupted: number;
}

export const formatRunSource = (run: Run, patch?: Patch, auditEvents: AuditEvent[] = []): RunSourceLabel => {
  if (patch?.source_snapshot_id || hasSnapshotSource(run, auditEvents)) {
    return "Snapshot rollback";
  }

  if (
    auditEvents.some(
      (event) => event.entity_type === "run" && event.entity_id === run.id && event.action === "drift.run"
    )
  ) {
    return "Drift check";
  }

  return "Schema apply";
};

export const runFailureReason = (run: Run): string | undefined => {
  const scriptError = run.scripts.find((script) => script.error)?.error;
  if (scriptError) {
    return scriptError.message;
  }
  if (run.status === "aborted") {
    return "Stopped before completion";
  }
  if (run.status === "failed") {
    return "A migration script failed";
  }
  return undefined;
};

export const buildRunTrend = (runs: Run[], now = Date.now()): RunTrendDay[] => {
  const dayMs = 24 * 60 * 60 * 1000;
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(now - (6 - index) * dayMs);
    const key = date.toISOString().slice(0, 10);
    return {
      key,
      label: date.toLocaleDateString(undefined, { weekday: "short" }),
      completed: 0,
      interrupted: 0
    } satisfies RunTrendDay;
  });
  const byKey = new Map(days.map((day) => [day.key, day]));

  runs.forEach((run) => {
    const day = byKey.get(new Date(run.started_at).toISOString().slice(0, 10));
    if (!day) return;
    if (run.status === "completed") day.completed += 1;
    if (run.status === "failed" || run.status === "aborted") day.interrupted += 1;
  });

  return days;
};

export const recentlyAbortedRuns = (runs: Run[], now = Date.now()): Run[] => {
  const sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000;
  return runs.filter((run) => run.status === "aborted" && Date.parse(run.started_at) >= sevenDaysAgo);
};

const hasSnapshotSource = (run: Run, auditEvents: AuditEvent[]): boolean =>
  auditEvents.some(
    (event) =>
      event.entity_type === "run" &&
      event.entity_id === run.id &&
      typeof event.metadata?.source_snapshot_id === "string"
  );
