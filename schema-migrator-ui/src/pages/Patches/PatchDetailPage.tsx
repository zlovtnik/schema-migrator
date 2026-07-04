import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowCounterClockwise";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { ActivityTable } from "../../components/ActivityTable";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { useAuditEvents } from "../../hooks/useAudit";
import { useDeletePatch, usePatch } from "../../hooks/usePatches";
import { useSession } from "../../hooks/useSession";
import { useRollbackToSnapshot } from "../../hooks/useSnapshots";

export const PatchDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: patch, isLoading, error } = usePatch(id);
  const { canMutate, canViewAudit } = useSession();
  const { data: activity = [], isLoading: activityLoading } = useAuditEvents(
    { entity_type: "patch", entity_id: id ?? null },
    canViewAudit && Boolean(id)
  );
  const deletePatch = useDeletePatch();
  const rollbackToSnapshot = useRollbackToSnapshot();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [rollbackConfirmOpen, setRollbackConfirmOpen] = useState(false);

  if (isLoading) {
    return <div className="page empty-state">Loading patch...</div>;
  }

  if (error || !patch) {
    return <div className="page status-banner status-banner--error">Patch could not be loaded.</div>;
  }

  const confirmDelete = () => {
    if (!canMutate) {
      return;
    }
    deletePatch.mutate(patch.id, {
      onSuccess: () => navigate(`/migrations?target=${patch.target_id}`)
    });
  };

  const confirmRollback = () => {
    if (!canMutate || !patch.source_snapshot_id) {
      return;
    }
    rollbackToSnapshot.mutate({
      snapshot_id: patch.source_snapshot_id,
      target_id: patch.target_id,
      source_type: "patch",
      source_id: patch.id
    });
  };

  return (
    <section className="page">
      <header className="page-header">
        <div>
        <span className="eyebrow">Migration detail</span>
          <h1>{patch.version}</h1>
          <p>{patch.label}</p>
        </div>
        <div className="row-actions">
          <StatusBadge status={patch.status} />
          {patch.source_snapshot_id ? (
            <button
              className="button button--secondary"
              type="button"
              onClick={() => setRollbackConfirmOpen(true)}
              disabled={!canMutate || rollbackToSnapshot.isPending}
              title={canMutate ? undefined : "Viewer role cannot start rollback runs"}
            >
              <Icon source={ArrowCounterClockwiseIcon} size={16} />
              Rollback to snapshot
            </button>
          ) : null}
          {patch.status === "pending" ? (
            <button
              className="button button--danger"
              type="button"
              onClick={() => setConfirmOpen(true)}
              disabled={!canMutate || deletePatch.isPending}
              title={canMutate ? undefined : "Viewer role cannot delete migrations"}
            >
              <Icon source={TrashIcon} size={16} />
              Delete migration
            </button>
          ) : null}
        </div>
      </header>

      <div className="detail-grid">
        <div>
          <span className="field-label">Target</span>
          <strong>{patch.target_id}</strong>
        </div>
        <div>
          <span className="field-label">Related runs</span>
          <Link to={`/runs?target=${patch.target_id}`}>View run history</Link>
        </div>
        <div>
          <span className="field-label">Source snapshot</span>
          {patch.source_snapshot_id ? (
            <Link className="object-type-chip" to={`/snapshots/${patch.source_snapshot_id}`}>
              Generated from snapshot #{patch.source_snapshot_id}
            </Link>
          ) : (
            <span className="cell-subtle">No snapshot reference</span>
          )}
        </div>
      </div>

      <div className="table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th scope="col">Order</th>
              <th scope="col">Filename</th>
              <th scope="col">Checksum</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {[...patch.scripts].sort((a, b) => a.order - b.order).map((script) => (
              <tr key={script.id}>
                <td>{script.order}</td>
                <td>{script.filename}</td>
                <td>
                  <code>{script.checksum}</code>
                </td>
                <td>
                  <StatusBadge status={script.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {canViewAudit ? (
        <section className="section-block">
          <h2>Activity</h2>
          {activityLoading ? (
            <div className="empty-state">Loading activity...</div>
          ) : (
            <ActivityTable events={activity} empty="No audit events recorded for this migration." />
          )}
        </section>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title="Delete migration"
        message={`Delete ${patch.version}? This cannot be undone.`}
        confirmLabel="Delete"
        destructive
        busy={deletePatch.isPending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={confirmDelete}
      />
      <ConfirmDialog
        open={rollbackConfirmOpen}
        title="Rollback to snapshot"
        message={`Start a rollback run for ${patch.version} back to snapshot ${patch.source_snapshot_id}?`}
        confirmLabel="Start rollback"
        busy={rollbackToSnapshot.isPending}
        onCancel={() => setRollbackConfirmOpen(false)}
        onConfirm={confirmRollback}
      />
    </section>
  );
};
