import { Link } from "react-router-dom";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { ActivityTable } from "../../components/ActivityTable";
import { StatusBadge } from "../../components/StatusBadge";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { useAuditEvents } from "../../hooks/useAudit";
import { useRuns } from "../../hooks/useRuns";
import { useSession } from "../../hooks/useSession";
import { useTargets } from "../../hooks/useTargets";

export const OverviewPage = () => {
  const { canViewAudit } = useSession();
  const { data: targets = [], isLoading: targetsLoading } = useTargets();
  const { data: runs = [], isLoading: runsLoading } = useRuns();
  const { data: auditEvents = [], isLoading: auditLoading, error: auditError } = useAuditEvents({ limit: 5 }, canViewAudit);

  const activeRuns = runs.filter((run) => run.status === "pending" || run.status === "running");
  const failedRuns = runs.filter((run) => run.status === "failed");
  const recentRuns = [...runs].sort((a, b) => Date.parse(b.started_at) - Date.parse(a.started_at)).slice(0, 5);

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Overview</span>
          <h1>Schema control</h1>
          <p>Targets, run safety, and validation state.</p>
        </div>
        <Link className="button button--primary" to="/migrations">
          Open migrations
        </Link>
      </header>

      <section className="metric-grid" aria-label="System health summary">
        <div>
          <span className="field-label">Targets</span>
          <strong>{targetsLoading ? "..." : targets.length}</strong>
        </div>
        <div>
          <span className="field-label">Active runs</span>
          <strong>{runsLoading ? "..." : activeRuns.length}</strong>
        </div>
        <div>
          <span className="field-label">Failed runs</span>
          <strong>{runsLoading ? "..." : failedRuns.length}</strong>
        </div>
      </section>

      {!runsLoading ? (
        failedRuns.length > 0 ? (
          <div className="status-banner status-banner--error">
            <Icon source={WarningIcon} size={20} weight="bold" />
            {failedRuns.length} failed run{failedRuns.length === 1 ? "" : "s"} need resolution before more applies.
          </div>
        ) : (
          <div className="status-banner status-banner--ok">
            <Icon source={ShieldCheckIcon} size={20} weight="bold" />
            No failed runs are blocking apply operations.
          </div>
        )
      ) : null}

      <section className="section-block">
        <h2>Recent runs</h2>
        {runsLoading ? (
          <div className="empty-state">Loading run history...</div>
        ) : recentRuns.length === 0 ? (
          <EmptyState icon={<Icon source={DatabaseIcon} size={24} />} title="No migration runs yet">
            Run history will appear here after a migration is started.
          </EmptyState>
        ) : (
          <div className="table-panel">
            <table className="data-table">
              <thead>
                <tr>
                  <th scope="col">Migration</th>
                  <th scope="col">Target</th>
                  <th scope="col">Started</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {recentRuns.map((run) => (
                  <tr key={run.id}>
                    <td>
                      <Link to={`/runs/${run.id}`}>{run.patch_id}</Link>
                    </td>
                    <td>{run.target_id}</td>
                    <td>{new Date(run.started_at).toLocaleString()}</td>
                    <td>
                      <StatusBadge status={run.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {canViewAudit ? (
        <section className="section-block">
          <h2>Recent activity</h2>
          {auditLoading ? (
            <div className="empty-state">Loading activity...</div>
          ) : auditError ? (
            <div className="status-banner status-banner--error">Audit activity could not be loaded.</div>
          ) : (
            <ActivityTable events={auditEvents.slice(0, 5)} empty="No audit events recorded yet." />
          )}
        </section>
      ) : null}
    </section>
  );
};
