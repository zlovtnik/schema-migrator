import { useMemo } from "react";
import { Link } from "react-router-dom";
import { ArrowClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowClockwise";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { GitDiffIcon } from "@phosphor-icons/react/dist/csr/GitDiff";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { ActivityTable } from "../../components/ActivityTable";
import { StatusBadge } from "../../components/StatusBadge";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useAuditEvents } from "../../hooks/useAudit";
import { usePatches, useRunPatch } from "../../hooks/usePatches";
import { useResolveRun, useRuns } from "../../hooks/useRuns";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { useTargets } from "../../hooks/useTargets";
import type { AuditEvent, Patch, Run, Target } from "../../types";
import {
  buildRunTrend,
  formatRunSource,
  recentlyAbortedRuns,
  runFailureReason,
  type RunTrendDay
} from "../../utils/runPresentation";

export const OverviewPage = () => {
  const { canMutate, canViewAudit } = useSession();
  const { data: targets = [], isLoading: targetsLoading } = useTargets();
  const { data: patches = [], isLoading: patchesLoading } = usePatches();
  const { data: runs = [], isLoading: runsLoading } = useRuns();
  const {
    data: auditEvents = [],
    isLoading: auditLoading,
    error: auditError
  } = useAuditEvents({ limit: 100 }, canViewAudit);
  const resolveRun = useResolveRun();
  const runPatch = useRunPatch();
  const selectedTargetId = useSelectedTargetId();

  const activeRuns = runs.filter((run) => run.status === "pending" || run.status === "running");
  const failedRuns = runs.filter((run) => run.status === "failed");
  const abortedRuns = recentlyAbortedRuns(runs);
  const recentRuns = [...runs].sort((a, b) => Date.parse(b.started_at) - Date.parse(a.started_at)).slice(0, 5);
  const appliedRuns = runs.filter((run) => run.status === "completed").length;
  const trend = buildRunTrend(runs);
  const loadingRuns = runsLoading || targetsLoading || patchesLoading;
  const driftUrl = selectedTargetId ? `/drift?target=${encodeURIComponent(selectedTargetId)}` : "/drift";

  const retry = (run: Run) => {
    const trigger = () => runPatch.mutate({ patch_id: run.patch_id, target_id: run.target_id });
    if (run.status === "failed") {
      resolveRun.mutate(run.id, { onSuccess: trigger });
      return;
    }
    trigger();
  };

  const retryDisabledReason = (run: Run): string | undefined => {
    if (!canMutate) return "Viewer role cannot retry runs";
    if (resolveRun.isPending || runPatch.isPending) return "A retry is already starting";
    if (activeRuns.some((activeRun) => activeRun.target_id === run.target_id)) {
      return "This target already has an active run";
    }
    return undefined;
  };

  const recentRunColumns = useMemo<DataTableColumn<Run>[]>(
    () => createRecentRunColumns(targets, patches, auditEvents, retry, retryDisabledReason),
    // Mutations intentionally refresh the columns so pending controls update immediately.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [activeRuns, auditEvents, canMutate, patches, resolveRun.isPending, runPatch.isPending, targets]
  );

  const healthTone = failedRuns.length > 0 ? "error" : abortedRuns.length > 0 ? "warning" : "ok";
  const healthTitle = runsLoading
    ? "Checking run safety"
    : failedRuns.length > 0
      ? "Run resolution needed"
      : abortedRuns.length > 0
        ? "Recent run interruptions"
        : "No runs currently blocked";
  const healthDescription = runsLoading
    ? "Loading run state before apply operations."
    : failedRuns.length > 0
      ? `${failedRuns.length} failed run${failedRuns.length === 1 ? "" : "s"} must be resolved before more applies.`
      : abortedRuns.length > 0
        ? `${abortedRuns.length} run${abortedRuns.length === 1 ? " was" : "s were"} aborted in the last 7 days; applies are not currently blocked.`
        : "No failed runs are blocking apply operations.";

  return (
    <section className="page overview-page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Overview</span>
          <h1>Schema control</h1>
          <p>Targets, run safety, and validation state.</p>
        </div>
      </header>

      <section className="overview-summary" aria-label="System health summary">
        <div className="system-health-card">
          <div className={`health-orb health-orb--${healthTone}`}>
            <Icon source={healthTone === "ok" ? ShieldCheckIcon : WarningIcon} size={20} weight="bold" />
          </div>
          <div>
            <strong>{healthTitle}</strong>
            <p>{healthDescription}</p>
          </div>
          <dl>
            <div>
              <dt>Applied</dt>
              <dd>{runsLoading ? "…" : appliedRuns}</dd>
            </div>
            <div>
              <dt>Active</dt>
              <dd>{runsLoading ? "…" : activeRuns.length}</dd>
            </div>
          </dl>
        </div>
      </section>

      <section className="section-block overview-runs">
        <div className="section-block__header overview-runs__header">
          <div>
            <h2>Recent runs</h2>
            <p>Migration outcomes across all configured targets.</p>
          </div>
          <Link className="button button--primary" to={driftUrl}>
            <Icon source={GitDiffIcon} size={16} weight="bold" />
            Open drift
          </Link>
        </div>

        <RunTrend days={trend} />

        {loadingRuns ? (
          <Skeleton rows={5} label="Loading recent runs" />
        ) : recentRuns.length === 0 ? (
          <EmptyState
            icon={<Icon source={DatabaseIcon} size={24} />}
            title="No runs yet"
            action={
              <Link className="button button--secondary" to={driftUrl}>
                Check for drift
              </Link>
            }
          >
            Run history will appear here after a schema apply or drift check starts.
          </EmptyState>
        ) : (
          <DataTable
            caption="Recent runs"
            columns={recentRunColumns}
            rows={recentRuns}
            rowKey={(run) => run.id}
            getRowState={(run) => ({
              className: run.status === "failed" || run.status === "aborted" ? "row--interrupted" : undefined
            })}
            empty="No runs yet"
          />
        )}
      </section>

      {canViewAudit ? (
        <details className="overview-activity">
          <summary>
            <span>
              <strong>Recent activity</strong>
              <small>Passive audit log</small>
            </span>
            <span className="overview-activity__count">
              {auditLoading
                ? "Loading"
                : `${Math.min(auditEvents.length, 5)} recent event${auditEvents.length === 1 ? "" : "s"}`}
            </span>
          </summary>
          <div className="overview-activity__body">
            {auditLoading ? (
              <Skeleton rows={4} label="Loading recent activity" />
            ) : auditError ? (
              <div className="status-banner status-banner--error">Audit activity could not be loaded.</div>
            ) : auditEvents.length === 0 ? (
              <EmptyState title="No recent activity">
                Audit events will appear here as schema operations run.
              </EmptyState>
            ) : (
              <ActivityTable
                compact
                events={auditEvents.slice(0, 5)}
                patches={patches}
                referenceEvents={auditEvents}
                runs={runs}
                targets={targets}
                empty="No audit events recorded yet."
              />
            )}
          </div>
        </details>
      ) : null}
    </section>
  );
};

const createRecentRunColumns = (
  targets: Target[],
  patches: Patch[],
  auditEvents: AuditEvent[],
  retry: (run: Run) => void,
  retryDisabledReason: (run: Run) => string | undefined
): DataTableColumn<Run>[] => [
  {
    id: "source",
    header: "Run source",
    sortValue: (run) =>
      formatRunSource(
        run,
        patches.find((patch) => patch.id === run.patch_id),
        auditEvents
      ),
    cell: (run) => {
      const patch = patches.find((item) => item.id === run.patch_id);
      return (
        <span className="run-source-cell">
          <Link to={`/runs/${run.id}`} title={`Patch ID: ${run.patch_id}`}>
            {formatRunSource(run, patch, auditEvents)}
          </Link>
          <small>
            {patch?.label || `${run.scripts.length} migration script${run.scripts.length === 1 ? "" : "s"}`}
          </small>
        </span>
      );
    }
  },
  {
    id: "target",
    header: "Target",
    sortValue: (run) => targets.find((target) => target.id === run.target_id)?.label ?? "Removed target",
    cell: (run) => {
      const target = targets.find((item) => item.id === run.target_id);
      return target ? (
        <Link to={`/targets/${target.id}/overview`} title={`Target ID: ${target.id}`}>
          {target.label}
        </Link>
      ) : (
        <span title={`Target ID: ${run.target_id}`}>Removed target</span>
      );
    }
  },
  {
    id: "started",
    header: "Started",
    sortValue: (run) => run.started_at,
    cell: (run) => <time dateTime={run.started_at}>{new Date(run.started_at).toLocaleString()}</time>
  },
  {
    id: "status",
    header: "Status",
    sortValue: (run) => run.status,
    cell: (run) => <StatusBadge status={run.status} />
  },
  {
    id: "outcome",
    header: "Outcome",
    sortValue: (run) => runFailureReason(run) ?? "Completed",
    cell: (run) => {
      const reason = runFailureReason(run);
      return reason ? (
        <Link className="run-reason-link" to={`/runs/${run.id}#failure-reason`} title={reason}>
          {reason}
        </Link>
      ) : (
        <span className="cell-subtle">Completed without interruption</span>
      );
    }
  },
  {
    id: "action",
    header: "Action",
    cell: (run) => {
      const retryReason = retryDisabledReason(run);
      return run.status === "failed" || run.status === "aborted" ? (
        <button
          className="button button--secondary button--small"
          type="button"
          disabled={Boolean(retryReason)}
          title={retryReason}
          onClick={() => retry(run)}
        >
          <Icon source={ArrowClockwiseIcon} size={16} weight="bold" />
          Retry
        </button>
      ) : (
        <Link className="button button--secondary button--small" to={`/runs/${run.id}`}>
          View
        </Link>
      );
    }
  }
];

const RunTrend = ({ days }: { days: RunTrendDay[] }) => {
  const maxDailyRuns = Math.max(1, ...days.map((day) => day.completed + day.interrupted));
  const completed = days.reduce((total, day) => total + day.completed, 0);
  const interrupted = days.reduce((total, day) => total + day.interrupted, 0);

  return (
    <div className="run-trend" aria-label={`7-day run outcomes: ${completed} completed, ${interrupted} interrupted`}>
      <div className="run-trend__header">
        <span>7-day outcomes</span>
        <div className="run-trend__legend" aria-hidden="true">
          <span>
            <i className="run-trend__key run-trend__key--completed" />
            {completed} completed
          </span>
          <span>
            <i className="run-trend__key run-trend__key--interrupted" />
            {interrupted} failed / aborted
          </span>
        </div>
      </div>
      <div className="run-trend__plot" aria-hidden="true">
        {days.map((day) => {
          const outcomeCount = day.completed + day.interrupted;
          const height = outcomeCount === 0 ? 3 : Math.max(16, Math.round((outcomeCount / maxDailyRuns) * 100));
          return (
            <div
              className="run-trend__day"
              key={day.key}
              title={`${day.label}: ${day.completed} completed, ${day.interrupted} failed or aborted`}
            >
              <div className="run-trend__track">
                <div className="run-trend__bar" style={{ height: `${height}%` }}>
                  {day.interrupted > 0 ? (
                    <span
                      className="run-trend__segment run-trend__segment--interrupted"
                      style={{ flexGrow: day.interrupted }}
                    />
                  ) : null}
                  {day.completed > 0 ? (
                    <span
                      className="run-trend__segment run-trend__segment--completed"
                      style={{ flexGrow: day.completed }}
                    />
                  ) : null}
                </div>
              </div>
              <span>{day.label}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};
