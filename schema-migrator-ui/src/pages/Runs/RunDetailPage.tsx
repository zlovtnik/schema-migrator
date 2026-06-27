import { useMemo } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Download, Square } from "lucide-react";
import { LogViewer } from "../../components/LogViewer";
import { ScriptProgressList } from "../../components/ScriptProgressList";
import { StatusBadge } from "../../components/StatusBadge";
import { useRunStream } from "../../hooks/useRunStream";
import { runKeys, useAbortRun, useRun } from "../../hooks/useRuns";

export const RunDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: run, isLoading, error } = useRun(id);
  const abortRun = useAbortRun();

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
            <button className="button button--danger" type="button" onClick={() => abortRun.mutate(run.id)}>
              <Square size={16} aria-hidden="true" />
              Abort
            </button>
          ) : null}
          <button className="button button--secondary" type="button" onClick={downloadLog} disabled={stream.logLines.length === 0}>
            <Download size={16} aria-hidden="true" />
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
    </section>
  );
};
