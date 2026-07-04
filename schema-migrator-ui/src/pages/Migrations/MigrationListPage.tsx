import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowDownIcon } from "@phosphor-icons/react/dist/csr/ArrowDown";
import { ArrowUpIcon } from "@phosphor-icons/react/dist/csr/ArrowUp";
import { PlayIcon } from "@phosphor-icons/react/dist/csr/Play";
import { UploadSimpleIcon } from "@phosphor-icons/react/dist/csr/UploadSimple";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { Icon } from "../../components/ui/Icon";
import { useErrorGate } from "../../hooks/useErrorGate";
import { useMigrations, useTriggerRun, useUploadMigration } from "../../hooks/useMigrations";
import { useMutationGuard } from "../../hooks/useMutationGuard";
import { useRuns } from "../../hooks/useRuns";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";

export const MigrationListPage = () => {
  const selectedTarget = useSelectedTargetId();
  const { data: migrations = [], isLoading, error } = useMigrations(selectedTarget);
  const { data: runs = [] } = useRuns(selectedTarget);
  const { isGateBlocked, failedRun } = useErrorGate();
  const { canMutate } = useSession();
  const mutationGuard = useMutationGuard(canMutate);
  const triggerRun = useTriggerRun();
  const uploadMigration = useUploadMigration();
  const [files, setFiles] = useState<File[]>([]);

  const isRunning = runs.some((run) => run.status === "running" || run.status === "pending");
  const canApply = Boolean(selectedTarget) && canMutate && !isGateBlocked && !isRunning && !triggerRun.isPending;

  useEffect(() => {
    setFiles([]);
  }, [selectedTarget]);

  const onFileSelect = (selected: FileList | null) => {
    if (!selected) {
      return;
    }
    setFiles(Array.from(selected).filter((file) => /\.sql$/i.test(file.name)));
  };

  const moveFile = (index: number, direction: -1 | 1) => {
    setFiles((previous) => {
      const next = [...previous];
      const target = index + direction;
      if (target < 0 || target >= next.length) {
        return previous;
      }
      const current = next[index];
      const swap = next[target];
      if (!current || !swap) {
        return previous;
      }
      next[index] = swap;
      next[target] = current;
      return next;
    });
  };

  const upload = () => {
    if (!canMutate || !selectedTarget || files.length === 0) {
      return;
    }
    uploadMigration.mutate(
      { target_id: selectedTarget, files },
      {
        onSuccess: () => setFiles([])
      }
    );
  };

  const sortedMigrations = useMemo(
    () => [...migrations].sort((a, b) => b.version.localeCompare(a.version)),
    [migrations]
  );
  const uploadGuard = mutationGuard("Viewer role cannot upload migrations", uploadMigration.isPending);

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Migrations</span>
          <h1>Migration files</h1>
        </div>
        <TargetSelector />
      </header>

      {isGateBlocked ? (
        <div className="status-banner status-banner--error">
          Apply is disabled by failed run {failedRun?.id}. Resolve it before applying migrations.
        </div>
      ) : null}
      {isRunning ? <div className="status-banner">Apply is disabled while this target has an active run.</div> : null}
      {!canMutate ? <div className="status-banner">Viewer role cannot upload or apply migrations.</div> : null}
      {!selectedTarget ? <div className="empty-state">Select a target to view migrations.</div> : null}
      {error ? <div className="status-banner status-banner--error">Unable to load migrations.</div> : null}
      {isLoading ? <div className="empty-state">Loading migrations...</div> : null}

      {selectedTarget ? (
        <section
          className="upload-zone"
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            onFileSelect(event.dataTransfer.files);
          }}
        >
          <Icon source={UploadSimpleIcon} size={24} />
          <div>
            <strong>Upload SQL migration files</strong>
            <span>Drop files here or select from disk, then order and confirm.</span>
          </div>
          <input
            type="file"
            accept=".sql"
            multiple
            disabled={!canMutate}
            title={canMutate ? undefined : "Viewer role cannot upload migrations"}
            onChange={(event) => onFileSelect(event.target.files)}
          />
        </section>
      ) : null}

      {files.length > 0 ? (
        <div className="table-panel">
          <div className="table-toolbar">
            <strong>Upload order</strong>
            <button
              className="button button--primary"
              type="button"
              onClick={upload}
              disabled={uploadGuard.disabled}
              title={uploadGuard.title}
            >
              {uploadMigration.isPending ? "Uploading" : "Confirm upload"}
            </button>
          </div>
          <ol className="ordered-files">
            {files.map((file, index) => (
              <li key={`${file.name}-${index}`}>
                <span>{file.name}</span>
                <div className="row-actions">
                  <button className="icon-button" type="button" onClick={() => moveFile(index, -1)} aria-label="Move up">
                    <Icon source={ArrowUpIcon} size={16} weight="bold" />
                  </button>
                  <button className="icon-button" type="button" onClick={() => moveFile(index, 1)} aria-label="Move down">
                    <Icon source={ArrowDownIcon} size={16} weight="bold" />
                  </button>
                </div>
              </li>
            ))}
          </ol>
        </div>
      ) : null}

      {selectedTarget && !isLoading && sortedMigrations.length === 0 ? (
        <div className="empty-state">No migrations registered for this target.</div>
      ) : null}

      {sortedMigrations.length > 0 ? (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Version</th>
                <th scope="col">Label</th>
                <th scope="col">Scripts</th>
                <th scope="col">Status</th>
                <th scope="col">Applied at</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {sortedMigrations.map((migration) => {
                const applyGuard = mutationGuard("Viewer role cannot apply migrations", !canApply || migration.status !== "pending");
                return (
                  <tr key={migration.id}>
                    <td>
                      <Link to={`/migrations/${migration.id}`}>{migration.version}</Link>
                    </td>
                    <td>{migration.label}</td>
                    <td>{migration.scripts.length}</td>
                    <td>
                      <StatusBadge status={migration.status} />
                    </td>
                    <td>{migration.applied_at ? new Date(migration.applied_at).toLocaleString() : "-"}</td>
                    <td>
                      <button
                        className="button button--primary button--small"
                        type="button"
                        disabled={applyGuard.disabled}
                        title={applyGuard.title}
                        onClick={() => triggerRun.mutate({ patch_id: migration.id, target_id: migration.target_id })}
                      >
                        <Icon source={PlayIcon} size={16} weight="fill" />
                        Apply
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  );
};
