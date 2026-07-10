import { useMemo } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { DownloadIcon } from "@phosphor-icons/react/dist/csr/Download";
import { SquareIcon } from "@phosphor-icons/react/dist/csr/Square";
import { ActivitySection } from "../../components/ActivitySection";
import { LogViewer } from "../../components/LogViewer";
import { ScriptProgressList } from "../../components/ScriptProgressList";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { useAuditEvents } from "../../hooks/useAudit";
import { useRunStream } from "../../hooks/useRunStream";
import { runKeys, useAbortRun, useRun } from "../../hooks/useRuns";
import { useDrift } from "../../hooks/useSchema";
import { useSession } from "../../hooks/useSession";
import type { Run, SchemaControlObject } from "../../types";

export const RunDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: run, isLoading, error } = useRun(id);
  const { data: drift } = useDrift(run?.target_id);
  const { canMutate, canViewAudit } = useSession();
  const { data: activity = [], isLoading: activityLoading } = useAuditEvents(
    { entity_type: "run", entity_id: id ?? null },
    canViewAudit && Boolean(id)
  );
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
    },
    onRunFailed: () => {
      if (id) {
        void queryClient.invalidateQueries({ queryKey: runKeys.detail(id) });
        void queryClient.invalidateQueries({ queryKey: runKeys.all });
      }
    }
  });

  const scripts = useMemo(() => stream.orderedScripts, [stream.orderedScripts]);
  const controlRows = useMemo(() => controlRowsForRun(run, drift?.control_objects ?? []), [drift?.control_objects, run]);

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
        <h2>Schema control</h2>
        <SchemaControlRunTable rows={controlRows} />
      </section>

      <section className="section-block">
        <h2>Log stream</h2>
        <LogViewer lines={stream.logLines} />
      </section>

      {canViewAudit ? <ActivitySection events={activity} isLoading={activityLoading} empty="No audit events recorded for this run." /> : null}
    </section>
  );
};

const SchemaControlRunTable = ({ rows }: { rows: SchemaControlObject[] }) => {
  const columns = useMemo<DataTableColumn<SchemaControlObject>[]>(
    () => [
      {
        id: "source",
        header: "Source file",
        sortValue: (row) => row.source_file,
        cell: (row) => <code title={row.source_file}>{row.source_file}</code>
      },
      {
        id: "object",
        header: "Object",
        sortValue: (row) => row.object_name,
        cell: (row) => <code title={row.object_name}>{row.object_name}</code>
      },
      {
        id: "kind",
        header: "Kind",
        sortValue: (row) => row.kind,
        cell: (row) => row.kind
      },
      {
        id: "status",
        header: "Control status",
        sortValue: (row) => row.apply_status,
        cell: (row) => <StatusBadge status={controlStatus(row.apply_status)} />
      },
      {
        id: "checksum",
        header: "Checksum",
        sortValue: (row) => row.checksum,
        cell: (row) => (
          <span className="hash-chip" title={row.checksum}>
            {row.checksum.slice(0, 10)}
          </span>
        )
      },
      {
        id: "updated",
        header: "Updated",
        sortValue: (row) => row.updated_at ?? "",
        cell: (row) => formatOptionalDate(row.updated_at)
      }
    ],
    []
  );

  return (
    <DataTable
      caption="Schema control rows for this run"
      columns={columns}
      rows={rows}
      rowKey={(row) => `${row.kind}:${row.object_name}:${row.source_file}`}
      empty="No schema_control rows were returned for this run's scripts."
    />
  );
};

const controlRowsForRun = (run: Run | undefined, rows: SchemaControlObject[]): SchemaControlObject[] => {
  if (!run) {
    return [];
  }
  const filenames = new Set(run.scripts.map((script) => script.filename));
  const matchingRows = rows.filter((row) => filenames.has(row.source_file));
  return matchingRows.length > 0 ? matchingRows : rows;
};

const controlStatus = (status: string): "pending" | "applied" | "failed" | "skipped" =>
  status === "applied" || status === "failed" || status === "skipped" ? status : "pending";

const formatOptionalDate = (value?: string | null): string => {
  if (!value) {
    return "-";
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? value : new Date(parsed).toLocaleString();
};
