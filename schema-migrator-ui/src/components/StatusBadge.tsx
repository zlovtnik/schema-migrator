import type { PatchStatus, RunStatus, ScriptStatus, Severity, ValidationStatus } from "../types";

type BadgeStatus = RunStatus | PatchStatus | ScriptStatus | Severity | ValidationStatus | "connected" | "untested";

interface StatusBadgeProps {
  status: BadgeStatus;
  title?: string;
}

const statusLabels: Record<string, string> = {
  pending: "Pending",
  running: "Running",
  completed: "Completed",
  failed: "Failed",
  aborted: "Aborted",
  applied: "Applied",
  partial: "Partial",
  skipped: "Skipped",
  warning: "Warning",
  error: "Error",
  clean: "Clean",
  warnings: "Warnings",
  errors: "Errors",
  connected: "Connected",
  untested: "Untested"
};

export const StatusBadge = ({ status, title }: StatusBadgeProps) => (
  <span className={`status-badge status-badge--${status}`} title={title}>
    <span className="status-badge__dot" aria-hidden="true" />
    {statusLabels[status] ?? status}
  </span>
);
