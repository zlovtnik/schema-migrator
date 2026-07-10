import type {
  DriftType,
  PatchStatus,
  RunStatus,
  SchemaObjectStatus,
  ScriptStatus,
  Severity,
  SnapshotDiffType,
  ValidationStatus
} from "../types";
import { CheckCircleIcon } from "@phosphor-icons/react/dist/csr/CheckCircle";
import { ClockIcon } from "@phosphor-icons/react/dist/csr/Clock";
import { LockSimpleIcon } from "@phosphor-icons/react/dist/csr/LockSimple";
import { MinusCircleIcon } from "@phosphor-icons/react/dist/csr/MinusCircle";
import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { XCircleIcon } from "@phosphor-icons/react/dist/csr/XCircle";
import { Icon, type IconSource } from "./ui/Icon";

type BadgeStatus =
  | RunStatus
  | PatchStatus
  | ScriptStatus
  | Severity
  | ValidationStatus
  | SchemaObjectStatus
  | DriftType
  | SnapshotDiffType
  | "connected"
  | "untested";

interface StatusBadgeProps {
  status: BadgeStatus;
  title?: string | undefined;
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
  defined: "Defined",
  in_sync: "In sync",
  drift_detected: "Drift detected",
  pending_migration: "Pending apply",
  unknown: "Unknown",
  missing_actual: "Missing actual",
  untracked_actual: "Untracked actual",
  definition_changed: "Definition changed",
  pending_or_failed_control: "Pending or failed",
  added: "Added",
  changed: "Changed",
  removed: "Removed",
  connected: "Connected",
  untested: "Untested"
};

const statusIcons: Record<string, IconSource> = {
  pending: ClockIcon,
  running: ClockIcon,
  completed: CheckCircleIcon,
  failed: XCircleIcon,
  aborted: XCircleIcon,
  applied: CheckCircleIcon,
  partial: WarningIcon,
  skipped: MinusCircleIcon,
  locked: LockSimpleIcon,
  warning: WarningIcon,
  error: XCircleIcon,
  clean: CheckCircleIcon,
  warnings: WarningIcon,
  errors: XCircleIcon,
  defined: ClockIcon,
  in_sync: CheckCircleIcon,
  drift_detected: WarningIcon,
  pending_migration: ClockIcon,
  unknown: MinusCircleIcon,
  missing_actual: WarningIcon,
  untracked_actual: WarningIcon,
  definition_changed: WarningIcon,
  pending_or_failed_control: WarningIcon,
  added: CheckCircleIcon,
  changed: WarningIcon,
  removed: MinusCircleIcon,
  connected: CheckCircleIcon,
  untested: ClockIcon
};

export const StatusBadge = ({ status, title }: StatusBadgeProps) => (
  <span className={`status-badge status-badge--${status}`} title={title}>
    <Icon source={statusIcons[status] ?? ClockIcon} size={16} weight="bold" />
    <span className="status-badge__label">{statusLabels[status] ?? status}</span>
  </span>
);
