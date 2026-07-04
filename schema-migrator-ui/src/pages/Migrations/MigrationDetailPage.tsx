import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowCounterClockwise";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { ActivitySection } from "../../components/ActivitySection";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { useAuditEvents } from "../../hooks/useAudit";
import { useDeleteMigration, useMigration } from "../../hooks/useMigrations";
import { useMutationGuard } from "../../hooks/useMutationGuard";
import { useRollbackAction } from "../../hooks/useRollbackAction";
import { useSession } from "../../hooks/useSession";
import type { RollbackToSnapshotPayload } from "../../types";

export const MigrationDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: migration, isLoading, error } = useMigration(id);
  const { canMutate, canViewAudit } = useSession();
  const { data: activity = [], isLoading: activityLoading } = useAuditEvents(
    { entity_type: "patch", entity_id: id ?? null },
    canViewAudit && Boolean(id)
  );
  const deleteMigration = useDeleteMigration();
  const mutationGuard = useMutationGuard(canMutate);
  const rollback = useRollbackAction();
  const [confirmOpen, setConfirmOpen] = useState(false);

  if (isLoading) {
    return <div className="page empty-state">Loading migration...</div>;
  }

  if (error || !migration) {
    return <div className="page status-banner status-banner--error">Migration could not be loaded.</div>;
  }

  const confirmDelete = () => {
    if (!canMutate) {
      return;
    }
    deleteMigration.mutate(migration.id, {
      onSuccess: () => navigate(`/migrations?target=${migration.target_id}`)
    });
  };

  const rollbackPayload: RollbackToSnapshotPayload | undefined = migration.source_snapshot_id
    ? {
        snapshot_id: migration.source_snapshot_id,
        target_id: migration.target_id,
        source_type: "patch",
        source_id: migration.id
      }
    : undefined;
  const rollbackGuard = mutationGuard("Viewer role cannot start rollback runs", rollback.isPending);
  const deleteGuard = mutationGuard("Viewer role cannot delete migrations", deleteMigration.isPending);

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Migration detail</span>
          <h1>{migration.version}</h1>
          <p>{migration.label}</p>
        </div>
        <div className="row-actions">
          <StatusBadge status={migration.status} />
          {migration.source_snapshot_id ? (
            <button
              className="button button--secondary"
              type="button"
              onClick={rollback.openConfirm}
              disabled={rollbackGuard.disabled}
              title={rollbackGuard.title}
            >
              <Icon source={ArrowCounterClockwiseIcon} size={16} />
              Rollback to snapshot
            </button>
          ) : null}
          {migration.status === "pending" ? (
            <button
              className="button button--danger"
              type="button"
              onClick={() => setConfirmOpen(true)}
              disabled={deleteGuard.disabled}
              title={deleteGuard.title}
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
          <strong>{migration.target_id}</strong>
        </div>
        <div>
          <span className="field-label">Related runs</span>
          <Link to={`/runs?target=${migration.target_id}`}>View run history</Link>
        </div>
        <div>
          <span className="field-label">Source snapshot</span>
          {migration.source_snapshot_id ? (
            <Link className="object-type-chip" to={`/snapshots/${migration.source_snapshot_id}`}>
              Generated from snapshot #{migration.source_snapshot_id}
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
            {[...migration.scripts].sort((a, b) => a.order - b.order).map((script) => (
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

      {canViewAudit ? <ActivitySection events={activity} isLoading={activityLoading} empty="No audit events recorded for this migration." /> : null}

      <ConfirmDialog
        open={confirmOpen}
        title="Delete migration"
        message={`Delete ${migration.version}? This cannot be undone.`}
        confirmLabel="Delete"
        destructive
        busy={deleteMigration.isPending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={confirmDelete}
      />
      <ConfirmDialog
        open={rollback.confirmOpen}
        title="Rollback to snapshot"
        message={`Start a rollback run for ${migration.version} back to snapshot ${migration.source_snapshot_id}?`}
        confirmLabel="Start rollback"
        busy={rollback.isPending}
        onCancel={rollback.closeConfirm}
        onConfirm={() => rollback.confirm(canMutate, rollbackPayload)}
      />
    </section>
  );
};
