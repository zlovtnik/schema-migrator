import { useEffect, useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { LiveRunCard } from "../../components/LiveRunCard";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { useAuditEvents } from "../../hooks/useAudit";
import { usePatches } from "../../hooks/usePatches";
import { useAbortRun, useResolveRun, useRuns } from "../../hooks/useRuns";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { useTargets } from "../../hooks/useTargets";
import { runStatusOptions, type Run, type RunStatus } from "../../types";
import { formatRunSource, runFailureReason } from "../../utils/runPresentation";

const formatDuration = (startedAt: string, endedAt?: string) => {
  if (!endedAt) {
    return "-";
  }
  const duration = Math.max(0, Date.parse(endedAt) - Date.parse(startedAt));
  return `${Math.round(duration / 1000)} s`;
};

export const RunListPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const targetId = useSelectedTargetId();
  const rawStatusFilter = searchParams.get("status");
  const statusFilter = runStatusOptions.includes(rawStatusFilter as RunStatus) ? (rawStatusFilter as RunStatus) : null;
  const { data: runs = [], isLoading, error } = useRuns(targetId);
  const { data: patches = [] } = usePatches(targetId);
  const { data: targets = [] } = useTargets();
  const abortRun = useAbortRun();
  const resolveRun = useResolveRun();
  const { canMutate, canViewAudit } = useSession();
  const { data: auditEvents = [] } = useAuditEvents({ limit: 250 }, canViewAudit);

  useEffect(() => {
    if (!rawStatusFilter || statusFilter) {
      return;
    }
    const next = new URLSearchParams(searchParams);
    next.delete("status");
    setSearchParams(next, { replace: true });
  }, [rawStatusFilter, searchParams, setSearchParams, statusFilter]);

  const filteredRuns = useMemo(
    () =>
      runs
        .filter((run) => !statusFilter || run.status === statusFilter)
        .sort((a, b) => Date.parse(b.started_at) - Date.parse(a.started_at)),
    [runs, statusFilter]
  );

  const activeRun =
    filteredRuns.find((run) => run.status === "running") ?? filteredRuns.find((run) => run.status === "pending");

  const setStatus = (status: string) => {
    const next = new URLSearchParams(searchParams);
    if (status) {
      next.set("status", status);
    } else {
      next.delete("status");
    }
    setSearchParams(next);
  };

  const columns: DataTableColumn<Run>[] = [
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
      id: "source",
      header: "Run source",
      sortValue: (run) =>
        formatRunSource(
          run,
          patches.find((patch) => patch.id === run.patch_id),
          auditEvents
        ),
      cell: (run) => (
        <Link to={`/runs/${run.id}`} title={`Patch ID: ${run.patch_id}`}>
          {formatRunSource(
            run,
            patches.find((patch) => patch.id === run.patch_id),
            auditEvents
          )}
        </Link>
      )
    },
    {
      id: "started",
      header: "Started",
      sortValue: (run) => run.started_at,
      cell: (run) => new Date(run.started_at).toLocaleString()
    },
    { id: "duration", header: "Duration", cell: (run) => formatDuration(run.started_at, run.ended_at) },
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
      cell: (run) =>
        run.status === "failed" ? (
          <button
            className="button button--secondary button--small"
            type="button"
            disabled={!canMutate || resolveRun.isPending}
            title={canMutate ? undefined : "Viewer role cannot resolve runs"}
            onClick={() => resolveRun.mutate(run.id)}
          >
            {resolveRun.isPending ? "Resolving" : "Resolve"}
          </button>
        ) : null
    }
  ];

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Runs</span>
          <h1>Run history</h1>
        </div>
        <div className="toolbar">
          <TargetSelector />
          <label>
            Status
            <select value={statusFilter ?? ""} onChange={(event) => setStatus(event.target.value)}>
              <option value="">All statuses</option>
              {runStatusOptions.map((status) => (
                <option value={status} key={status}>
                  {status}
                </option>
              ))}
            </select>
          </label>
        </div>
      </header>

      {activeRun ? (
        <LiveRunCard
          run={activeRun}
          sourceLabel={formatRunSource(
            activeRun,
            patches.find((patch) => patch.id === activeRun.patch_id),
            auditEvents
          )}
          onAbort={(runId) => abortRun.mutate(runId)}
          aborting={abortRun.isPending}
        />
      ) : null}

      {error ? <div className="status-banner status-banner--error">Unable to load runs.</div> : null}
      {isLoading ? <div className="empty-state">Loading runs...</div> : null}
      {!isLoading && filteredRuns.length === 0 ? <div className="empty-state">No runs match this filter.</div> : null}

      {filteredRuns.length > 0 ? (
        <DataTable
          caption="Run history"
          columns={columns}
          rows={filteredRuns}
          rowKey={(run) => run.id}
          getRowState={(run) => ({ className: run.status === "failed" ? "row--failed" : undefined })}
          empty="No runs match this filter."
        />
      ) : null}
    </section>
  );
};
