import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { GitDiffIcon } from "@phosphor-icons/react/dist/csr/GitDiff";
import { PageHeader } from "../../components/PageHeader";
import { SnapshotDiffTable } from "../../components/SnapshotDiffTable";
import { StatusBadge } from "../../components/StatusBadge";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useSnapshotDiff } from "../../hooks/useSnapshots";
import { snapshotDiffTypeOptions, type SnapshotDiffItem, type SnapshotDiffType } from "../../types";

type SnapshotDiffFilter = SnapshotDiffType | "all";

export const SnapshotDiffPage = () => {
  const { id, otherId } = useParams();
  const { data: diff, isLoading, error } = useSnapshotDiff(id, otherId);
  const [filter, setFilter] = useState<SnapshotDiffFilter>("all");

  const counts = useMemo(() => countByDiffType(diff?.items ?? []), [diff?.items]);
  const items = useMemo(
    () => (diff?.items ?? []).filter((item) => filter === "all" || item.diff_type === filter),
    [diff?.items, filter]
  );

  if (isLoading) {
    return <div className="page"><Skeleton rows={6} label="Loading snapshot diff" /></div>;
  }

  if (error || !diff || !id || !otherId) {
    return <div className="page status-banner status-banner--error">Snapshot diff could not be loaded.</div>;
  }

  return (
    <section className="page">
      <PageHeader
        eyebrow="Snapshot diff"
        title="File changes"
        description={diff.generated_at ? `Generated ${new Date(diff.generated_at).toLocaleString()}.` : undefined}
        actions={
          <>
            <Link className="button button--secondary" to={`/snapshots/${id}`}>
              Base snapshot
            </Link>
            <Link className="button button--secondary" to={`/snapshots/${otherId}`}>
              Compare snapshot
            </Link>
          </>
        }
      />

      <div className="drift-toolbar">
        <div>
          <span className="eyebrow">Compare</span>
          <div className="snapshot-id-pair">
            <code>{diff.snapshot_id}</code>
            <span>against</span>
            <code>{diff.other_snapshot_id}</code>
          </div>
        </div>
        <div className="drift-chip-row" aria-label="Snapshot diff filters">
          <button
            className={filter === "all" ? "drift-chip drift-chip--active" : "drift-chip"}
            type="button"
            aria-pressed={filter === "all"}
            onClick={() => setFilter("all")}
          >
            <span>All</span>
            <strong>{diff.items.length}</strong>
          </button>
          {snapshotDiffTypeOptions.map((type) => (
            <button
              className={filter === type ? "drift-chip drift-chip--active" : "drift-chip"}
              key={type}
              type="button"
              aria-pressed={filter === type}
              onClick={() => setFilter(type)}
            >
              <StatusBadge status={type} />
              <strong>{counts.get(type) ?? 0}</strong>
            </button>
          ))}
        </div>
      </div>

      {diff.items.length === 0 ? (
        <EmptyState icon={<Icon source={GitDiffIcon} size={24} />} title="No file changes">
          These snapshots contain the same file paths and hashes.
        </EmptyState>
      ) : (
        <SnapshotDiffTable items={items} empty="No file changes match this filter." />
      )}
    </section>
  );
};

const countByDiffType = (items: SnapshotDiffItem[]): Map<SnapshotDiffType, number> => {
  const counts = new Map<SnapshotDiffType, number>();
  items.forEach((item) => counts.set(item.diff_type, (counts.get(item.diff_type) ?? 0) + 1));
  return counts;
};
