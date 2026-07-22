import type { AuditEvent, Patch, Run, Target } from "../types";
import { formatRunSource } from "./runPresentation";

const actionLabels: Record<string, string> = {
  "drift.run": "Drift check started",
  "patch.create_from_sql_files": "Schema patch created",
  "patch.delete": "Patch deleted",
  "patch.upload": "Patch uploaded",
  "rollback.trigger": "Snapshot rollback started",
  "run.abort": "Run aborted",
  "run.complete": "Run completed",
  "run.fail": "Run failed",
  "run.resolve": "Run failure resolved",
  "run.trigger": "Run started",
  "snapshot.create": "Snapshot created",
  "target.create": "Target created",
  "target.delete": "Target deleted",
  "target.update": "Target updated",
  "validation.rerun": "Validation rerun started",
  "validation.sql_files": "SQL files validated",
  "validation.validate": "Run validated"
};

const systemActors = new Set(["", "unknown", "system", "schema-migrator"]);
const scheduledActors = new Set(["cron", "scheduler", "scheduled"]);
const opaqueIdPattern = /^(?:[0-9a-f]{8}-[0-9a-f-]{27}|[0-9a-f]{24})$/i;

export interface ActivityReferenceData {
  auditEvents?: AuditEvent[];
  patches?: Patch[];
  runs?: Run[];
  targets?: Target[];
}

export const formatActivityAction = (action: string): string =>
  actionLabels[action] ?? sentenceCase(action.split(".").at(-1)?.replaceAll("_", " ") ?? action);

export const formatActivityActor = (event: AuditEvent): string => {
  const normalized = event.actor.trim().toLowerCase();
  if (scheduledActors.has(normalized)) {
    return "Scheduled";
  }
  if (systemActors.has(normalized)) {
    return "System";
  }
  if (opaqueIdPattern.test(normalized)) {
    return "Authenticated user";
  }
  return event.actor;
};

export const formatActivityEntity = (event: AuditEvent, references: ActivityReferenceData = {}): string => {
  const target = references.targets?.find((item) => item.id === event.entity_id);
  if (event.entity_type === "target") {
    return target?.label ?? "Target";
  }

  const run = references.runs?.find((item) => item.id === event.entity_id);
  if (event.entity_type === "run") {
    if (!run) return "Run";
    const patch = references.patches?.find((item) => item.id === run.patch_id);
    return formatRunSource(run, patch, references.auditEvents);
  }

  const patch = references.patches?.find((item) => item.id === event.entity_id);
  if (event.entity_type === "patch") {
    return patch?.label || "Schema patch";
  }

  if (event.entity_type === "snapshot") {
    return metadataLabel(event, "snapshot_label") ?? "Snapshot";
  }
  if (event.entity_type === "validation") {
    return "Validation report";
  }

  return sentenceCase(event.entity_type.replaceAll("_", " "));
};

const metadataLabel = (event: AuditEvent, key: string): string | undefined => {
  const value = event.metadata?.[key];
  return typeof value === "string" && value.trim() ? value : undefined;
};

const sentenceCase = (value: string): string => {
  const normalized = value.trim();
  return normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : "Activity";
};
