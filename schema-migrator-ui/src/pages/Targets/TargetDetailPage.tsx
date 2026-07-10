import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { GitDiffIcon } from "@phosphor-icons/react/dist/csr/GitDiff";
import { PageHeader } from "../../components/PageHeader";
import { StatusBadge } from "../../components/StatusBadge";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useRuns } from "../../hooks/useRuns";
import { useSession } from "../../hooks/useSession";
import { useSnapshots } from "../../hooks/useSnapshots";
import { useTarget } from "../../hooks/useTargets";
import type { Run, Snapshot } from "../../types";

type TargetTab = "overview" | "runs" | "validation" | "snapshots";

const tabs: Array<{ id: TargetTab; label: string }> = [
  { id: "overview", label: "Overview" },
  { id: "runs", label: "Runs" },
  { id: "validation", label: "Validation" },
  { id: "snapshots", label: "Snapshots" }
];

export const TargetDetailPage = () => {
  const { id } = useParams();
  const { canManageTargets } = useSession();
  const [activeTab, setActiveTab] = useState<TargetTab>("overview");
  const { data: target, isLoading: targetLoading, error: targetError } = useTarget(id);
  const { data: runs = [], isLoading: runsLoading } = useRuns(id);
  const { data: snapshots = [], isLoading: snapshotsLoading } = useSnapshots(id);

  const sortedRuns = useMemo(
    () => [...runs].sort((a, b) => Date.parse(b.started_at) - Date.parse(a.started_at)),
    [runs]
  );
  const sortedSnapshots = useMemo(
    () => [...snapshots].sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at)),
    [snapshots]
  );
  const latestCompletedRun = sortedRuns.find((run) => run.status === "completed");

  if (targetLoading) {
    return <div className="page"><Skeleton rows={6} label="Loading target" /></div>;
  }

  if (targetError || !target || !id) {
    return <div className="page status-banner status-banner--error">Target could not be loaded.</div>;
  }

  return (
    <section className="page">
      <PageHeader
        eyebrow="Target control"
        title={target.label}
        description={`${target.app_name} · ${target.env}`}
        actions={
          <Link
            className="button button--secondary"
            to={`/targets/${target.id}`}
            aria-disabled={!canManageTargets}
            title={canManageTargets ? undefined : "Admin role required to edit target credentials"}
            onClick={(event) => {
              if (!canManageTargets) event.preventDefault();
            }}
          >
            Edit credentials
          </Link>
        }
      />

      <div className="target-tabs" role="tablist" aria-label="Target detail views">
        {tabs.map((tab) => (
          <button
            className={activeTab === tab.id ? "target-tab target-tab--active" : "target-tab"}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.id}
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === "overview" ? (
        <TargetOverviewTab
          runCount={runs.length}
          snapshotCount={snapshots.length}
          latestCompletedRun={latestCompletedRun}
          loading={runsLoading || snapshotsLoading}
          recentRuns={sortedRuns.slice(0, 5)}
          recentSnapshots={sortedSnapshots.slice(0, 5)}
        />
      ) : null}
      {activeTab === "runs" ? <RunTable runs={sortedRuns} loading={runsLoading} /> : null}
      {activeTab === "validation" ? <ValidationTab runs={sortedRuns} loading={runsLoading} /> : null}
      {activeTab === "snapshots" ? <SnapshotTable snapshots={sortedSnapshots} loading={snapshotsLoading} /> : null}
    </section>
  );
};

const TargetOverviewTab = ({
  latestCompletedRun,
  loading,
  recentRuns,
  recentSnapshots,
  runCount,
  snapshotCount
}: {
  latestCompletedRun: Run | undefined;
  loading: boolean;
  recentRuns: Run[];
  recentSnapshots: Snapshot[];
  runCount: number;
  snapshotCount: number;
}) => (
  <div className="target-overview">
    <section className="metric-grid" aria-label="Target summary">
      <div>
        <span className="field-label">Runs</span>
        <strong>{loading ? "..." : runCount}</strong>
      </div>
      <div>
        <span className="field-label">Snapshots</span>
        <strong>{loading ? "..." : snapshotCount}</strong>
      </div>
    </section>

    <div className="detail-grid">
      <div>
        <span className="field-label">Latest validation</span>
        {latestCompletedRun ? (
          <Link to={`/validation/${latestCompletedRun.id}`}>Run {latestCompletedRun.id}</Link>
        ) : (
          <span className="cell-subtle">No completed run</span>
        )}
      </div>
      <div>
        <span className="field-label">Snapshot history</span>
        {recentSnapshots[0] ? (
          <Link to={`/snapshots/${recentSnapshots[0].id}`}>{recentSnapshots[0].label}</Link>
        ) : (
          <span className="cell-subtle">No snapshots</span>
        )}
      </div>
    </div>

    <div className="target-overview-grid">
      <section className="section-block">
        <h2>Recent runs</h2>
        <RunTable runs={recentRuns} loading={loading} compact />
      </section>
    </div>
  </div>
);

const runColumns: DataTableColumn<Run>[] = [
  {
    id: "patch",
    header: "Run source",
    sortValue: (run) => run.patch_id,
    cell: (run) => <Link to={`/runs/${run.id}`}>{run.patch_id}</Link>
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
  }
];

const RunTable = ({ compact = false, loading, runs }: { compact?: boolean; loading: boolean; runs: Run[] }) => {
  if (loading) return <Skeleton rows={compact ? 3 : 6} label="Loading runs" />;
  return (
    <DataTable
      caption="Target runs"
      columns={runColumns}
      rows={runs}
      rowKey={(run) => run.id}
      empty="No runs recorded for this target."
    />
  );
};

const ValidationTab = ({ loading, runs }: { loading: boolean; runs: Run[] }) => {
  const completedRuns = runs.filter((run) => run.status === "completed");
  if (loading) return <Skeleton rows={6} label="Loading validation links" />;
  if (completedRuns.length === 0) {
    return (
      <EmptyState icon={<Icon source={DatabaseIcon} size={24} />} title="No completed runs">
        Validation reports appear after runs complete.
      </EmptyState>
    );
  }
  return <RunTable runs={completedRuns} loading={false} />;
};

const snapshotColumns: DataTableColumn<Snapshot>[] = [
  {
    id: "label",
    header: "Label",
    sortValue: (snapshot) => snapshot.label,
    cell: (snapshot) => <Link to={`/snapshots/${snapshot.id}`}>{snapshot.label}</Link>
  },
  {
    id: "created",
    header: "Created",
    sortValue: (snapshot) => snapshot.created_at,
    cell: (snapshot) => <time dateTime={snapshot.created_at}>{new Date(snapshot.created_at).toLocaleString()}</time>
  },
  {
    id: "files",
    header: "Files",
    sortValue: (snapshot) => snapshot.file_count,
    cell: (snapshot) => snapshot.file_count
  },
  {
    id: "actions",
    header: "Actions",
    cell: (snapshot) => (
      <Link className="button button--secondary button--small" to={`/snapshots/${snapshot.id}`}>
        <Icon source={GitDiffIcon} size={16} />
        Inspect
      </Link>
    )
  }
];

const SnapshotTable = ({ loading, snapshots }: { loading: boolean; snapshots: Snapshot[] }) => {
  if (loading) return <Skeleton rows={6} label="Loading snapshots" />;
  return (
    <DataTable
      caption="Target snapshots"
      columns={snapshotColumns}
      rows={snapshots}
      rowKey={(snapshot) => snapshot.id}
      empty="No snapshots captured for this target."
    />
  );
};
