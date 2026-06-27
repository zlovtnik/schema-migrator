import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowDown, ArrowUp, Play, UploadCloud } from "lucide-react";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { useErrorGate } from "../../hooks/useErrorGate";
import { usePatches, useTriggerRun, useUploadPatch } from "../../hooks/usePatches";
import { useRuns } from "../../hooks/useRuns";

export const PatchListPage = () => {
  const [searchParams] = useSearchParams();
  const selectedTarget = searchParams.get("target");
  const { data: patches = [], isLoading, error } = usePatches(selectedTarget);
  const { data: runs = [] } = useRuns(selectedTarget);
  const { isGateBlocked, failedRun } = useErrorGate();
  const triggerRun = useTriggerRun();
  const uploadPatch = useUploadPatch();
  const [files, setFiles] = useState<File[]>([]);

  const isRunning = runs.some((run) => run.status === "running" || run.status === "pending");
  const canApply = Boolean(selectedTarget) && !isGateBlocked && !isRunning && !triggerRun.isPending;

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
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const upload = () => {
    if (!selectedTarget || files.length === 0) {
      return;
    }
    uploadPatch.mutate(
      { target_id: selectedTarget, files },
      {
        onSuccess: () => setFiles([])
      }
    );
  };

  const sortedPatches = useMemo(
    () => [...patches].sort((a, b) => b.version.localeCompare(a.version)),
    [patches]
  );

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Patches</span>
          <h1>Patch sets</h1>
        </div>
        <TargetSelector />
      </header>

      {isGateBlocked ? (
        <div className="status-banner status-banner--error">
          Apply is disabled by failed run {failedRun?.id}. Resolve it before applying patches.
        </div>
      ) : null}
      {isRunning ? <div className="status-banner">Apply is disabled while this target has an active run.</div> : null}
      {!selectedTarget ? <div className="empty-state">Select a target to view patches.</div> : null}
      {error ? <div className="status-banner status-banner--error">Unable to load patches.</div> : null}
      {isLoading ? <div className="empty-state">Loading patches...</div> : null}

      {selectedTarget ? (
        <section
          className="upload-zone"
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            onFileSelect(event.dataTransfer.files);
          }}
        >
          <UploadCloud size={22} aria-hidden="true" />
          <div>
            <strong>Upload SQL patch files</strong>
            <span>Drop files here or select from disk, then order and confirm.</span>
          </div>
          <input type="file" accept=".sql" multiple onChange={(event) => onFileSelect(event.target.files)} />
        </section>
      ) : null}

      {files.length > 0 ? (
        <div className="table-panel">
          <div className="table-toolbar">
            <strong>Upload order</strong>
            <button className="button button--primary" type="button" onClick={upload} disabled={uploadPatch.isPending}>
              {uploadPatch.isPending ? "Uploading" : "Confirm upload"}
            </button>
          </div>
          <ol className="ordered-files">
            {files.map((file, index) => (
              <li key={`${file.name}-${index}`}>
                <span>{file.name}</span>
                <div className="row-actions">
                  <button className="icon-button" type="button" onClick={() => moveFile(index, -1)} aria-label="Move up">
                    <ArrowUp size={15} />
                  </button>
                  <button className="icon-button" type="button" onClick={() => moveFile(index, 1)} aria-label="Move down">
                    <ArrowDown size={15} />
                  </button>
                </div>
              </li>
            ))}
          </ol>
        </div>
      ) : null}

      {selectedTarget && !isLoading && sortedPatches.length === 0 ? (
        <div className="empty-state">No patches registered for this target.</div>
      ) : null}

      {sortedPatches.length > 0 ? (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Version</th>
                <th>Label</th>
                <th>Scripts</th>
                <th>Status</th>
                <th>Applied at</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sortedPatches.map((patch) => (
                <tr key={patch.id}>
                  <td>
                    <Link to={`/patches/${patch.id}`}>{patch.version}</Link>
                  </td>
                  <td>{patch.label}</td>
                  <td>{patch.scripts.length}</td>
                  <td>
                    <StatusBadge status={patch.status} />
                  </td>
                  <td>{patch.applied_at ? new Date(patch.applied_at).toLocaleString() : "-"}</td>
                  <td>
                    <button
                      className="button button--primary button--small"
                      type="button"
                      disabled={!canApply || patch.status !== "pending"}
                      onClick={() => triggerRun.mutate({ patch_id: patch.id, target_id: patch.target_id })}
                    >
                      <Play size={14} aria-hidden="true" />
                      Apply
                    </button>
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
