import { useEffect, useMemo, useState } from "react";
import { SquareIcon } from "@phosphor-icons/react/dist/csr/Square";
import type { Run } from "../types";
import { useQueryClient } from "@tanstack/react-query";
import { useRunStream } from "../hooks/useRunStream";
import { runKeys } from "../hooks/useRuns";
import { StatusBadge } from "./StatusBadge";
import { Icon } from "./ui/Icon";
import { ProgressBar } from "./ui/ProgressBar";

interface LiveRunCardProps {
  run: Run;
  onAbort: (runId: string) => void;
  aborting?: boolean;
}

const formatElapsed = (seconds: number) => {
  const minutes = Math.floor(seconds / 60);
  const remaining = seconds % 60;
  return `${minutes}:${remaining.toString().padStart(2, "0")}`;
};

export const LiveRunCard = ({ run, onAbort, aborting = false }: LiveRunCardProps) => {
  const queryClient = useQueryClient();
  const stream = useRunStream(run.id, run, {
    enabled: run.status === "running" || run.status === "pending",
    onRunComplete: () => {
      void queryClient.invalidateQueries({ queryKey: runKeys.all });
      void queryClient.invalidateQueries({ queryKey: runKeys.detail(run.id) });
    },
    onRunFailed: () => {
      void queryClient.invalidateQueries({ queryKey: runKeys.all });
      void queryClient.invalidateQueries({ queryKey: runKeys.detail(run.id) });
    }
  });
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const totalScripts = Math.max(run.scripts.length, stream.orderedScripts.length);
  const completedScripts = stream.orderedScripts.filter((script) =>
    ["completed", "failed", "skipped"].includes(script.status)
  ).length;
  const elapsedSeconds = Math.max(0, Math.floor((now - Date.parse(run.started_at)) / 1000));

  const activeScript = useMemo(
    () => stream.orderedScripts.find((script) => script.status === "running"),
    [stream.orderedScripts]
  );

  return (
    <section className="live-run" aria-label="Active run">
      <div className="live-run__header">
        <div>
          <span className="eyebrow">Active run</span>
          <h2>Patch {run.patch_id}</h2>
        </div>
        <StatusBadge status={stream.runStatus} />
      </div>
      <ProgressBar
        label="Migration run progress"
        liveText={
          activeScript
            ? `Applying migration ${completedScripts + 1} of ${Math.max(totalScripts, 1)}: ${activeScript.filename}`
            : `${completedScripts} of ${totalScripts} scripts complete`
        }
        max={Math.max(totalScripts, 1)}
        value={completedScripts}
      />
      <div className="live-run__meta">
        <span>{completedScripts} / {totalScripts} scripts</span>
        <span>Elapsed {formatElapsed(elapsedSeconds)}</span>
        <span>{activeScript ? activeScript.filename : "Waiting for script event"}</span>
      </div>
      <button className="button button--danger" type="button" onClick={() => onAbort(run.id)} disabled={aborting}>
        <Icon source={SquareIcon} size={16} weight="fill" />
        {aborting ? "Aborting" : "Abort"}
      </button>
    </section>
  );
};
