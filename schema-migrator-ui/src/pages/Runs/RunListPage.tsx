import { useEffect, useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { LiveRunCard } from "../../components/LiveRunCard";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { useAbortRun, useResolveRun, useRuns } from "../../hooks/useRuns";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { runStatusOptions, type RunStatus } from "../../types";

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
  const abortRun = useAbortRun();
  const resolveRun = useResolveRun();
  const { canMutate } = useSession();

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
        <LiveRunCard run={activeRun} onAbort={(runId) => abortRun.mutate(runId)} aborting={abortRun.isPending} />
      ) : null}

      {error ? <div className="status-banner status-banner--error">Unable to load runs.</div> : null}
      {isLoading ? <div className="empty-state">Loading runs...</div> : null}
      {!isLoading && filteredRuns.length === 0 ? <div className="empty-state">No runs match this filter.</div> : null}

      {filteredRuns.length > 0 ? (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Target</th>
                <th scope="col">Run source</th>
                <th scope="col">Started</th>
                <th scope="col">Duration</th>
                <th scope="col">Status</th>
                <th scope="col">Action</th>
              </tr>
            </thead>
            <tbody>
              {filteredRuns.map((run) => (
                <tr className={run.status === "failed" ? "row--failed" : undefined} key={run.id}>
                  <td>{run.target_id}</td>
                  <td>
                    <Link to={`/runs/${run.id}`}>{run.patch_id}</Link>
                  </td>
                  <td>{new Date(run.started_at).toLocaleString()}</td>
                  <td>{formatDuration(run.started_at, run.ended_at)}</td>
                  <td>
                    <StatusBadge status={run.status} />
                  </td>
                  <td>
                    {run.status === "failed" ? (
                      <button
                        className="button button--secondary button--small"
                        type="button"
                        disabled={!canMutate || resolveRun.isPending}
                        title={canMutate ? undefined : "Viewer role cannot resolve runs"}
                        onClick={() => resolveRun.mutate(run.id)}
                      >
                        {resolveRun.isPending ? "Resolving" : "Resolve"}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  );
};
