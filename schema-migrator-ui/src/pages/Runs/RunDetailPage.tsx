import { useMemo } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowCounterClockwise";
import { DownloadIcon } from "@phosphor-icons/react/dist/csr/Download";
import { SquareIcon } from "@phosphor-icons/react/dist/csr/Square";
import { ActivitySection } from "../../components/ActivitySection";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { LogViewer } from "../../components/LogViewer";
import { ScriptProgressList } from "../../components/ScriptProgressList";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { useAuditEvents } from "../../hooks/useAudit";
import { usePatch } from "../../hooks/usePatches";
import { useRollbackAction } from "../../hooks/useRollbackAction";
import { useRunStream } from "../../hooks/useRunStream";
import { runKeys, useAbortRun, useRun } from "../../hooks/useRuns";
import { useSession } from "../../hooks/useSession";
import type { RollbackToSnapshotPayload } from "../../types";

export const RunDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: run, isLoading, error } = useRun(id);
  const { data: patch } = usePatch(run?.patch_id);
  const { canMutate, canViewAudit } = useSession();
  const { data: activity = [], isLoading: activityLoading } = useAuditEvents(
    { entity_type: "run", entity_id: id ?? null },
    canViewAudit && Boolean(id)
  );
  const abortRun = useAbortRun();
  const rollback = useRollbackAction();

  const stream = useRunStream(id, run, {
    enabled: run?.status === "running" || run?.status === "pending",
    onRunComplete: (event) => {
      if (id) {
        void queryClient.invalidateQueries({ queryKey: runKeys.detail(id) });
        void queryClient.invalidateQueries({ queryKey: runKeys.all });
      }
      if (event.validation_triggered) {
        navigate(`/validation/${event.run_id}`);
      }
    },
    onRunFailed: () => {
      if (id) {
        void queryClient.invalidateQueries({ queryKey: runKeys.detail(id) });
        void queryClient.invalidateQueries({ queryKey: runKeys.all });
      }
    }
  });

  const scripts = useMemo(() => stream.orderedScripts, [stream.orderedScripts]);

  const downloadLog = () => {
    if (!id) {
      return;
    }
    const blob = new Blob([stream.logLines.join("\n")], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `run-${id}.txt`;
    link.click();
    URL.revokeObjectURL(url);
  };

  if (isLoading) {
    return <div className="page empty-state">Loading run...</div>;
  }

  if (error || !run) {
    return <div className="page status-banner status-banner--error">Run could not be loaded.</div>;
  }

  const duration =
    run.ended_at && run.started_at ? `${Math.round((Date.parse(run.ended_at) - Date.parse(run.started_at)) / 1000)} s` : "-";
  const canAbort = stream.runStatus === "running" || stream.runStatus === "pending";
  const snapshotId = patch?.source_snapshot_id;

  const rollbackPayload: RollbackToSnapshotPayload | undefined = snapshotId
    ? {
        snapshot_id: snapshotId,
        target_id: run.target_id,
        source_type: "run",
        source_id: run.id
      }
    : undefined;

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Run detail</span>
          <h1>{run.patch_id}</h1>
          <p>Target {run.target_id} · duration {duration}</p>
        </div>
        <div className="row-actions">
          <StatusBadge status={stream.runStatus} />
          {canAbort ? (
            <button
              className="button button--danger"
              type="button"
              onClick={() => abortRun.mutate(run.id)}
              disabled={!canMutate}
              title={canMutate ? undefined : "Viewer role cannot abort runs"}
            >
              <Icon source={SquareIcon} size={16} weight="fill" />
              Abort
            </button>
          ) : null}
          {snapshotId ? (
            <button
              className="button button--secondary"
              type="button"
              onClick={rollback.openConfirm}
              disabled={!canMutate || canAbort || rollback.isPending}
              title={canMutate ? undefined : "Viewer role cannot start rollback runs"}
            >
              <Icon source={ArrowCounterClockwiseIcon} size={16} />
              Rollback to snapshot
            </button>
          ) : null}
          <button className="button button--secondary" type="button" onClick={downloadLog} disabled={stream.logLines.length === 0}>
            <Icon source={DownloadIcon} size={16} />
            Download log
          </button>
          {stream.runStatus === "completed" ? (
            <Link className="button button--primary" to={`/validation/${run.id}`}>
              Validation
            </Link>
          ) : null}
        </div>
      </header>

      <section className="section-block">
        <h2>Script progress</h2>
        <ScriptProgressList scripts={scripts} />
      </section>

      <section className="section-block">
        <h2>Log stream</h2>
        <LogViewer lines={stream.logLines} />
      </section>

      {canViewAudit ? <ActivitySection events={activity} isLoading={activityLoading} empty="No audit events recorded for this run." /> : null}

      <ConfirmDialog
        open={rollback.confirmOpen}
        title="Rollback to snapshot"
        message={`Start a rollback run for ${run.patch_id} back to snapshot ${snapshotId}?`}
        confirmLabel="Start rollback"
        busy={rollback.isPending}
        onCancel={rollback.closeConfirm}
        onConfirm={() => rollback.confirm(canMutate, rollbackPayload)}
      />
    </section>
  );
};
