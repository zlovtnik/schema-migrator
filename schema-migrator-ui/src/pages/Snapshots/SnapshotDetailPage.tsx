import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowCounterClockwise";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { GitDiffIcon } from "@phosphor-icons/react/dist/csr/GitDiff";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { PageHeader } from "../../components/PageHeader";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useSession } from "../../hooks/useSession";
import { useRollbackToSnapshot, useSnapshot, useSnapshots } from "../../hooks/useSnapshots";
import type { SnapshotFile } from "../../types";

export const SnapshotDetailPage = () => {
  const { id } = useParams();
  const { canMutate } = useSession();
  const { data: snapshot, isLoading, error } = useSnapshot(id);
  const { data: snapshots = [] } = useSnapshots(snapshot?.target_id);
  const rollbackToSnapshot = useRollbackToSnapshot();
  const [otherId, setOtherId] = useState("");
  const [rollbackConfirmOpen, setRollbackConfirmOpen] = useState(false);

  const otherSnapshots = useMemo(
    () => snapshots.filter((candidate) => candidate.id !== snapshot?.id),
    [snapshot?.id, snapshots]
  );
  const selectedOther = otherId || otherSnapshots[0]?.id || "";

  const files = useMemo(
    () => [...(snapshot?.files ?? [])].sort((a, b) => a.path.localeCompare(b.path)),
    [snapshot?.files]
  );

  const columns = useMemo<DataTableColumn<SnapshotFile>[]>(
    () => [
      {
        id: "path",
        header: "Path",
        sortValue: (file) => file.path,
        cell: (file) => <code title={file.path}>{file.path}</code>
      },
      {
        id: "sha256",
        header: "SHA-256",
        sortValue: (file) => file.sha256,
        cell: (file) => <code title={file.sha256}>{file.sha256}</code>
      }
    ],
    []
  );

  if (isLoading) {
    return <div className="page"><Skeleton rows={6} label="Loading snapshot" /></div>;
  }

  if (error || !snapshot) {
    return <div className="page status-banner status-banner--error">Snapshot could not be loaded.</div>;
  }

  const confirmRollback = () => {
    if (!canMutate) {
      return;
    }
    rollbackToSnapshot.mutate({
      snapshot_id: snapshot.id,
      target_id: snapshot.target_id
    });
  };

  return (
    <section className="page">
      <PageHeader
        eyebrow="Snapshot detail"
        title={snapshot.label}
        description={`Created ${new Date(snapshot.created_at).toLocaleString()} by ${snapshot.created_by}.`}
        actions={
          <>
            <button
              className="button button--secondary"
              type="button"
              onClick={() => setRollbackConfirmOpen(true)}
              disabled={!canMutate || rollbackToSnapshot.isPending}
              title={canMutate ? undefined : "Viewer role cannot start rollback runs"}
            >
              <Icon source={ArrowCounterClockwiseIcon} size={16} />
              Rollback
            </button>
            <select
              aria-label="Diff against another snapshot"
              value={selectedOther}
              onChange={(event) => setOtherId(event.target.value)}
              disabled={otherSnapshots.length === 0}
            >
              {otherSnapshots.length === 0 ? <option value="">No comparison</option> : null}
              {otherSnapshots.map((candidate) => (
                <option value={candidate.id} key={candidate.id}>
                  {candidate.label}
                </option>
              ))}
            </select>
            {selectedOther ? (
              <Link className="button button--primary" to={`/snapshots/${snapshot.id}/diff/${selectedOther}`}>
                <Icon source={GitDiffIcon} size={16} />
                Diff
              </Link>
            ) : (
              <button className="button button--primary" type="button" disabled>
                <Icon source={GitDiffIcon} size={16} />
                Diff
              </button>
            )}
          </>
        }
      />

      <div className="detail-grid">
        <div>
          <span className="field-label">Target</span>
          <strong>{snapshot.target_id}</strong>
        </div>
        <div>
          <span className="field-label">Files</span>
          <strong>{snapshot.file_count}</strong>
        </div>
      </div>

      {files.length === 0 ? (
        <EmptyState icon={<Icon source={FileSqlIcon} size={24} />} title="No file list returned">
          This snapshot exists, but the backend did not include file details.
        </EmptyState>
      ) : (
        <DataTable
          caption="Snapshot files"
          columns={columns}
          rows={files}
          rowKey={(file) => file.path}
          empty="No files were captured in this snapshot."
        />
      )}
      <ConfirmDialog
        open={rollbackConfirmOpen}
        title="Rollback to snapshot"
        message={`Start a rollback run for target ${snapshot.target_id} back to snapshot ${snapshot.label}?`}
        confirmLabel="Start rollback"
        busy={rollbackToSnapshot.isPending}
        onCancel={() => setRollbackConfirmOpen(false)}
        onConfirm={confirmRollback}
      />
    </section>
  );
};
