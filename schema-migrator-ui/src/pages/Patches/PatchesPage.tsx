import { useCallback, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { PlayIcon } from "@phosphor-icons/react/dist/csr/Play";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { PageHeader } from "../../components/PageHeader";
import { SqlFileTargetPicker } from "../../components/SqlFileTargetPicker";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { listSqlFiles } from "../../api/sqlFiles";
import { useErrorGate } from "../../hooks/useErrorGate";
import { useMutationGuard } from "../../hooks/useMutationGuard";
import { useCreatePatchFromSqlFiles, useDeletePatch, usePatch, usePatches, useRunPatch } from "../../hooks/usePatches";
import { useRuns } from "../../hooks/useRuns";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { useQuery } from "@tanstack/react-query";
import type { Patch, Script } from "../../types";

export const PatchesPage = () => {
  const { id } = useParams();
  return id ? <PatchDetail patchId={id} /> : <PatchList />;
};

type PatchMessage = { type: "success" | "error"; text: string };

const PatchList = () => {
  const selectedTarget = useSelectedTargetId();
  const { canMutate } = useSession();
  const mutationGuard = useMutationGuard(canMutate);
  const { data: patches = [], isLoading, error } = usePatches(selectedTarget);
  const { data: runs = [] } = useRuns(selectedTarget);
  const { isGateBlocked, failedRun } = useErrorGate(selectedTarget);
  const filesQuery = useQuery({
    queryKey: ["sql-files", selectedTarget || "none"],
    queryFn: () => listSqlFiles(selectedTarget as string),
    enabled: Boolean(selectedTarget)
  });
  const createPatch = useCreatePatchFromSqlFiles();
  const runPatch = useRunPatch();
  const deletePatch = useDeletePatch();
  const [selectedPaths, setSelectedPaths] = useState<string[]>([]);
  const [message, setMessage] = useState<PatchMessage | null>(null);

  const sortedPatches = useMemo(
    () => [...patches].sort((a, b) => b.version.localeCompare(a.version)),
    [patches]
  );
  const activeRun = runs.some((run) => run.status === "running" || run.status === "pending");
  const createGuard = mutationGuard(
    "Viewer role cannot create patches",
    !selectedTarget || selectedPaths.length === 0 || createPatch.isPending
  );

  const create = () => {
    if (!selectedTarget || createGuard.disabled) return;
    setMessage(null);
    createPatch.mutate(
      { target_id: selectedTarget, source_files: selectedPaths },
      {
        onSuccess: (patch) => {
          setSelectedPaths([]);
          setMessage({
            type: "success",
            text: `Created patch ${patch.id.slice(0, 8)} from ${patch.scripts.length} files`
          });
        },
        onError: (err) =>
          setMessage({ type: "error", text: err instanceof Error ? err.message : "Patch could not be created" })
      }
    );
  };

  const runDisabledReason = useCallback((patch: Patch): string | undefined => {
    if (!canMutate) return "Viewer role cannot run patches";
    if (patch.status !== "pending") return "Only pending patches can be run";
    if (isGateBlocked) return `Resolve failed run ${failedRun?.id ?? ""} before starting another run`;
    if (activeRun) return "This target already has an active run";
    if (runPatch.isPending) return "A run is already starting";
    return undefined;
  }, [activeRun, canMutate, failedRun?.id, isGateBlocked, runPatch.isPending]);

  const columns = useMemo<DataTableColumn<Patch>[]>(
    () => [
      {
        id: "label",
        header: "Patch",
        sortValue: (patch) => patch.version,
        cell: (patch) => <Link to={`/patches/${patch.id}`}>{patch.label || patch.id}</Link>
      },
      {
        id: "version",
        header: "Version",
        sortValue: (patch) => patch.version,
        cell: (patch) => <code>{patch.version}</code>
      },
      {
        id: "scripts",
        header: "Scripts",
        sortValue: (patch) => patch.scripts.length,
        cell: (patch) => patch.scripts.length
      },
      {
        id: "status",
        header: "Status",
        sortValue: (patch) => patch.status,
        cell: (patch) => <StatusBadge status={patch.status} />
      },
      {
        id: "actions",
        header: "Actions",
        cell: (patch) => {
          const disabledReason = runDisabledReason(patch);
          return (
            <div className="row-actions">
              <button
                className="button button--secondary button--small"
                type="button"
                disabled={Boolean(disabledReason)}
                title={disabledReason}
                onClick={() => runPatch.mutate({ target_id: patch.target_id, patch_id: patch.id })}
              >
                <Icon source={PlayIcon} size={16} weight="fill" />
                Run
              </button>
              <button
                className="button button--danger button--small"
                type="button"
                disabled={!canMutate || patch.status !== "pending" || deletePatch.isPending}
                title={!canMutate ? "Viewer role cannot delete patches" : undefined}
                onClick={() => deletePatch.mutate(patch.id)}
              >
                <Icon source={TrashIcon} size={16} />
                Delete
              </button>
            </div>
          );
        }
      }
    ],
    [canMutate, deletePatch, runDisabledReason, runPatch]
  );

  return (
    <section className="page">
      <PageHeader
        eyebrow="Operate"
        title="Patches"
        description="Create executable patches from synced repository SQL files and start controlled runs."
        actions={<TargetSelector />}
      />

      {!selectedTarget ? (
        <EmptyState icon={<Icon source={FileSqlIcon} size={24} />} title="Select a target">
          Choose a target to create or inspect patches.
        </EmptyState>
      ) : null}

      {message ? (
        <div className={message.type === "error" ? "status-banner status-banner--error" : "status-banner"}>
          {message.text}
        </div>
      ) : null}
      {error ? <div className="status-banner status-banner--error">Patches could not be loaded.</div> : null}
      {isGateBlocked ? (
        <div className="status-banner status-banner--error">
          Patch runs are disabled by failed run {failedRun?.id}. Resolve it before starting another run.
        </div>
      ) : null}
      {activeRun ? <div className="status-banner">Patch runs are disabled while this target has an active run.</div> : null}

      {selectedTarget ? (
        <section className="section-block">
          <div className="section-block__header">
            <div>
              <h2>Create patch</h2>
              <p>Select synced SQL files. The server orders them with the migrator discovery rules.</p>
            </div>
            <button
              className="button button--primary"
              type="button"
              disabled={createGuard.disabled}
              title={
                !canMutate
                  ? createGuard.title
                  : selectedPaths.length === 0
                    ? "Select at least one SQL file"
                    : undefined
              }
              onClick={create}
            >
              <Icon source={FileSqlIcon} size={16} />
              {createPatch.isPending ? "Creating" : `Create patch (${selectedPaths.length})`}
            </button>
          </div>
          {filesQuery.isLoading ? <Skeleton rows={5} label="Loading SQL files" /> : null}
          {filesQuery.error ? <div className="status-banner status-banner--error">SQL files could not be loaded.</div> : null}
          {filesQuery.data?.files.length ? (
            <SqlFileTargetPicker
              files={filesQuery.data.files}
              selectedPaths={selectedPaths}
              onSelectedPathsChange={setSelectedPaths}
              disabled={!canMutate || createPatch.isPending}
            />
          ) : !filesQuery.isLoading ? (
            <EmptyState icon={<Icon source={FileSqlIcon} size={24} />} title="No synced SQL files">
              Sync repository SQL files before creating a patch.
            </EmptyState>
          ) : null}
        </section>
      ) : null}

      {selectedTarget && isLoading ? <Skeleton rows={6} label="Loading patches" /> : null}
      {selectedTarget && !isLoading ? (
        <DataTable
          caption="Patches"
          columns={columns}
          rows={sortedPatches}
          rowKey={(patch) => patch.id}
          empty="No patches have been created for this target."
        />
      ) : null}
    </section>
  );
};

const PatchDetail = ({ patchId }: { patchId: string }) => {
  const navigate = useNavigate();
  const { canMutate } = useSession();
  const { data: patch, isLoading, error } = usePatch(patchId);
  const { data: runs = [] } = useRuns(patch?.target_id);
  const { isGateBlocked, failedRun } = useErrorGate(patch?.target_id);
  const runPatch = useRunPatch();
  const deletePatch = useDeletePatch();
  const activeRun = runs.some((run) => run.status === "running" || run.status === "pending");

  const runDisabledReason = (() => {
    if (!patch) return "Patch is still loading";
    if (!canMutate) return "Viewer role cannot run patches";
    if (patch.status !== "pending") return "Only pending patches can be run";
    if (isGateBlocked) return `Resolve failed run ${failedRun?.id ?? ""} before starting another run`;
    if (activeRun) return "This target already has an active run";
    return runPatch.isPending ? "A run is already starting" : undefined;
  })();

  const scriptColumns = useMemo<DataTableColumn<Script>[]>(
    () => [
      {
        id: "order",
        header: "Order",
        sortValue: (script) => script.order,
        cell: (script) => script.order
      },
      {
        id: "file",
        header: "File",
        sortValue: (script) => script.filename,
        cell: (script) => <code>{script.filename}</code>
      },
      {
        id: "checksum",
        header: "Checksum",
        sortValue: (script) => script.checksum,
        cell: (script) => <code>{script.checksum.slice(0, 12)}</code>
      },
      {
        id: "status",
        header: "Status",
        sortValue: (script) => script.status,
        cell: (script) => <StatusBadge status={script.status} />
      }
    ],
    []
  );

  if (isLoading) return <div className="page"><Skeleton rows={6} label="Loading patch" /></div>;
  if (error || !patch) return <div className="page status-banner status-banner--error">Patch could not be loaded.</div>;

  return (
    <section className="page">
      <PageHeader
        eyebrow="Patch"
        title={patch.label || patch.id}
        description={`Target ${patch.target_id} · version ${patch.version}`}
        actions={
          <div className="row-actions">
            <StatusBadge status={patch.status} />
            <button
              className="button button--primary"
              type="button"
              disabled={Boolean(runDisabledReason)}
              title={runDisabledReason}
              onClick={() => runPatch.mutate({ target_id: patch.target_id, patch_id: patch.id })}
            >
              <Icon source={PlayIcon} size={16} weight="fill" />
              {runPatch.isPending ? "Starting" : "Run patch"}
            </button>
            <button
              className="button button--danger"
              type="button"
              disabled={!canMutate || patch.status !== "pending" || deletePatch.isPending}
              title={!canMutate ? "Viewer role cannot delete patches" : undefined}
              onClick={() => deletePatch.mutate(patch.id, { onSuccess: () => navigate("/patches") })}
            >
              <Icon source={TrashIcon} size={16} />
              Delete
            </button>
          </div>
        }
      />

      {isGateBlocked ? (
        <div className="status-banner status-banner--error">
          Patch runs are disabled by failed run {failedRun?.id}. Resolve it before starting another run.
        </div>
      ) : null}

      <DataTable
        caption="Patch scripts"
        columns={scriptColumns}
        rows={patch.scripts}
        rowKey={(script) => script.id}
        empty="This patch has no scripts."
      />
    </section>
  );
};
