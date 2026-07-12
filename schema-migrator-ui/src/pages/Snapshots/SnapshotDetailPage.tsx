import { useCallback, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowCounterClockwise";
import { CheckIcon } from "@phosphor-icons/react/dist/csr/Check";
import { CopyIcon } from "@phosphor-icons/react/dist/csr/Copy";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { GitDiffIcon } from "@phosphor-icons/react/dist/csr/GitDiff";
import { MagnifyingGlassIcon } from "@phosphor-icons/react/dist/csr/MagnifyingGlass";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { DocumentTitle } from "../../components/DocumentTitle";
import { PageHeader } from "../../components/PageHeader";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useMutationGuard } from "../../hooks/useMutationGuard";
import { useRollbackAction } from "../../hooks/useRollbackAction";
import { useSession } from "../../hooks/useSession";
import { useSnapshot, useSnapshots } from "../../hooks/useSnapshots";
import { useTarget } from "../../hooks/useTargets";
import type { RollbackToSnapshotPayload, SnapshotFile } from "../../types";

export const SnapshotDetailPage = () => {
  const { id } = useParams();
  const { canMutate } = useSession();
  const { data: snapshot, isLoading, error } = useSnapshot(id);
  const { data: target } = useTarget(snapshot?.target_id);
  const { data: snapshots = [] } = useSnapshots(snapshot?.target_id);
  const mutationGuard = useMutationGuard(canMutate);
  const rollback = useRollbackAction();
  const [otherId, setOtherId] = useState("");
  const [pathFilter, setPathFilter] = useState("");
  const [copiedHash, setCopiedHash] = useState<string | null>(null);

  const otherSnapshots = useMemo(
    () => snapshots.filter((candidate) => candidate.id !== snapshot?.id),
    [snapshot?.id, snapshots]
  );
  const selectedOther = otherId || otherSnapshots[0]?.id || "";

  const files = useMemo(
    () => [...(snapshot?.files ?? [])].sort((a, b) => a.path.localeCompare(b.path)),
    [snapshot?.files]
  );
  const filteredFiles = useMemo(() => {
    const query = pathFilter.trim().toLowerCase();
    if (!query) {
      return files;
    }
    return files.filter((file) => file.path.toLowerCase().includes(query));
  }, [files, pathFilter]);
  const groupFileByFolder = useCallback((file: SnapshotFile) => {
    const folder = topLevelFolder(file.path);
    return { id: folder, label: folder, sortLabel: folder };
  }, []);

  const columns = useMemo<DataTableColumn<SnapshotFile>[]>(
    () => [
      {
        id: "path",
        header: "Path",
        sortValue: (file) => file.path,
        className: "snapshot-file-path-cell",
        cell: (file) => <code title={file.path}>{file.path}</code>
      },
      {
        id: "sha256",
        header: "SHA-256",
        sortValue: (file) => file.sha256,
        className: "snapshot-file-hash-cell",
        cell: (file) => (
          <HashCopyButton
            hash={file.sha256}
            copied={copiedHash === file.sha256}
            onCopy={() => {
              if (!navigator.clipboard) {
                return;
              }
              void navigator.clipboard.writeText(file.sha256).then(() => {
                setCopiedHash(file.sha256);
                window.setTimeout(() => setCopiedHash((current) => (current === file.sha256 ? null : current)), 1600);
              });
            }}
          />
        )
      }
    ],
    [copiedHash]
  );

  if (isLoading) {
    return (
      <div className="page">
        <Skeleton rows={6} label="Loading snapshot" />
      </div>
    );
  }

  if (error || !snapshot) {
    return <div className="page status-banner status-banner--error">Snapshot could not be loaded.</div>;
  }

  const rollbackGuard = mutationGuard("Viewer role cannot start rollback runs", rollback.isPending);
  const rollbackPayload: RollbackToSnapshotPayload = {
    snapshot_id: snapshot.id,
    target_id: snapshot.target_id
  };
  const snapshotTitle = `Snapshot · ${formatSnapshotTitleDate(snapshot.created_at)}`;
  const createdAtLabel = new Date(snapshot.created_at).toLocaleString();
  const actorLabel = formatSnapshotActor(snapshot.created_by);
  const comparisonHint = otherSnapshots.length === 0 ? "Select a second snapshot to compare." : undefined;
  const targetLabel = target?.label ?? "Unresolved target";

  return (
    <>
      <DocumentTitle title={snapshotTitle} />
      <section className="page">
        <PageHeader
          eyebrow="Snapshot detail"
          title={snapshotTitle}
          description={actorLabel ? `Created ${createdAtLabel} by ${actorLabel}.` : `Created ${createdAtLabel}.`}
          actions={
            <>
              <button
                className="button button--secondary"
                type="button"
                onClick={rollback.openConfirm}
                disabled={rollbackGuard.disabled}
                title={rollbackGuard.title}
              >
                <Icon source={ArrowCounterClockwiseIcon} size={16} />
                Rollback
              </button>
              <div className="snapshot-compare-controls">
                <select
                  aria-describedby={comparisonHint ? "snapshot-compare-hint" : undefined}
                  aria-label="Diff against another snapshot"
                  value={selectedOther}
                  onChange={(event) => setOtherId(event.target.value)}
                  disabled={otherSnapshots.length === 0}
                  title={comparisonHint}
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
                  <button className="button button--primary" type="button" disabled title={comparisonHint}>
                    <Icon source={GitDiffIcon} size={16} />
                    Diff
                  </button>
                )}
                {comparisonHint ? (
                  <span className="control-hint" id="snapshot-compare-hint">
                    {comparisonHint}
                  </span>
                ) : null}
              </div>
            </>
          }
        />

        <div className="detail-grid">
          <div>
            <span className="field-label">Target</span>
            <strong>{targetLabel}</strong>
            <code className="detail-secondary" title={snapshot.target_id}>
              {snapshot.target_id}
            </code>
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
            rows={filteredFiles}
            rowKey={(file) => file.path}
            empty={
              pathFilter ? "No snapshot files match this path filter." : "No files were captured in this snapshot."
            }
            groupBy={groupFileByFolder}
            groupSummary={(count) => `${count} ${count === 1 ? "file" : "files"}`}
            toolbar={
              <>
                <label className="snapshot-file-filter">
                  <span>
                    <Icon source={MagnifyingGlassIcon} size={16} />
                    Filter paths
                  </span>
                  <input
                    data-list-filter
                    placeholder="Search by path"
                    type="search"
                    value={pathFilter}
                    onChange={(event) => setPathFilter(event.target.value)}
                  />
                </label>
                <span className="snapshot-file-count">
                  {filteredFiles.length === files.length
                    ? `${files.length} files`
                    : `${filteredFiles.length} of ${files.length} files`}
                </span>
              </>
            }
            virtualizeThreshold={50}
          />
        )}
        <ConfirmDialog
          open={rollback.confirmOpen}
          title="Rollback to snapshot"
          message={`Start a rollback run for ${target?.label ?? snapshot.target_id} back to ${snapshotTitle}?`}
          confirmLabel="Start rollback"
          busy={rollback.isPending}
          onCancel={rollback.closeConfirm}
          onConfirm={() => rollback.confirm(canMutate, rollbackPayload)}
        />
      </section>
    </>
  );
};

const formatSnapshotTitleDate = (value: string): string => {
  const date = new Date(value);
  const options: Intl.DateTimeFormatOptions = {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  };
  if (date.getFullYear() !== new Date().getFullYear()) {
    options.year = "numeric";
  }
  return new Intl.DateTimeFormat(undefined, options).format(date);
};

const serviceActorLabels: Record<string, string> = {
  "api-token": "API integration",
  "service-token": "API integration",
  "static-api-token": "API integration"
};

const hiddenSystemActors = new Set(["", "system", "schema-migrator", "unknown"]);

const formatSnapshotActor = (actor: string): string | null => {
  const normalized = actor.trim().toLowerCase();
  if (hiddenSystemActors.has(normalized)) {
    return null;
  }
  return serviceActorLabels[normalized] ?? actor;
};

const topLevelFolder = (path: string): string => {
  const [folder] = path.split("/");
  return folder && folder !== path ? `${folder}/` : "(root)";
};

const HashCopyButton = ({ hash, copied, onCopy }: { hash: string; copied: boolean; onCopy: () => void }) => (
  <button aria-label="Copy full SHA-256" className="hash-copy-button" type="button" title={hash} onClick={onCopy}>
    <code>{truncateHash(hash)}</code>
    <Icon source={copied ? CheckIcon : CopyIcon} size={16} weight={copied ? "bold" : "regular"} />
  </button>
);

const truncateHash = (hash: string): string => (hash.length > 16 ? `${hash.slice(0, 6)}...${hash.slice(-6)}` : hash);
