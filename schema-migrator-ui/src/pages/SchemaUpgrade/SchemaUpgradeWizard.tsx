import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { ArrowClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowClockwise";
import { CheckCircleIcon } from "@phosphor-icons/react/dist/csr/CheckCircle";
import { GitBranchIcon } from "@phosphor-icons/react/dist/csr/GitBranch";
import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { createPatchFromSqlFiles } from "../../api/patches";
import { getRepoSyncStatus } from "../../api/repoSync";
import { triggerRun } from "../../api/runs";
import { getSchemaObjects } from "../../api/schemaObjects";
import { getValidation, rerunValidation, validateSqlFiles } from "../../api/validation";
import { LogViewer } from "../../components/LogViewer";
import { ScriptProgressList } from "../../components/ScriptProgressList";
import { StatusBadge } from "../../components/StatusBadge";
import { ValidationTable } from "../../components/ValidationTable";
import { folderIconSource } from "../../components/sqlFileTree";
import { Icon } from "../../components/ui/Icon";
import { Stepper } from "../../components/ui/Stepper";
import { useRunStream } from "../../hooks/useRunStream";
import { runKeys, useRun } from "../../hooks/useRuns";
import { useSelectedTarget, useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { useTargets } from "../../hooks/useTargets";
import { useWizardSteps } from "../../hooks/useWizardSteps";
import type { Run, SchemaObjectListItem, SqlFilesValidationResult, ValidationResult } from "../../types";
import { UpgradeSummary } from "./UpgradeSummary";

const steps = ["Choose objects", "Pre-check", "Execute", "Summary"] as const;
const terminalStatuses = new Set(["completed", "failed", "aborted"]);

export const SchemaUpgradeWizard = () => {
  const selectedTargetId = useSelectedTargetId();
  const { setSelectedTargetId } = useSelectedTarget();
  const [searchParams, setSearchParams] = useSearchParams();
  const { canMutate } = useSession();
  const targets = useTargets();
  const queryClient = useQueryClient();
  const {
    currentIndex: step,
    goTo: goToStep,
    next: nextStep,
    back: previousStep,
    reset: resetSteps
  } = useWizardSteps(steps);
  const [selectedFiles, setSelectedFiles] = useState<Set<string>>(new Set());
  const [precheck, setPrecheck] = useState<SqlFilesValidationResult>();
  const [runId, setRunId] = useState<string>();
  const [postcheck, setPostcheck] = useState<ValidationResult>();
  const initializedTarget = useRef<string | undefined>(undefined);

  const objectsQuery = useQuery({
    queryKey: ["schema", "objects", selectedTargetId ?? "none"],
    queryFn: () => getSchemaObjects(selectedTargetId as string),
    enabled: Boolean(selectedTargetId)
  });
  const repoQuery = useQuery({
    queryKey: ["repo-sync", "status", selectedTargetId ?? "none"],
    queryFn: () => getRepoSyncStatus(selectedTargetId as string),
    enabled: Boolean(selectedTargetId)
  });
  const runQuery = useRun(runId);

  const resetForTarget = (targetId: string) => {
    setSelectedTargetId(targetId);
    const nextSearchParams = new URLSearchParams(searchParams);
    if (targetId) nextSearchParams.set("target", targetId);
    else nextSearchParams.delete("target");
    setSearchParams(nextSearchParams, { replace: true });
  };

  const updateSelectedFiles = (files: Set<string>) => {
    setSelectedFiles(files);
    setPrecheck(undefined);
  };

  const validation = useMutation({
    mutationFn: () => {
      if (!selectedTargetId) throw new Error("Select a target before validating.");
      return validateSqlFiles({ target_id: selectedTargetId, source_files: [...selectedFiles] });
    },
    onSuccess: (result) => setPrecheck(result)
  });
  const execution = useMutation({
    mutationFn: async (): Promise<Run> => {
      if (!selectedTargetId) throw new Error("Select a target before executing.");
      const repo = await getRepoSyncStatus(selectedTargetId);
      if (!repo.last_synced_commit) throw new Error("Sync the target repository before executing an upgrade.");
      if (repo.drift)
        throw new Error("The synced SQL files are behind the remote repository. Sync again before executing.");
      const patch = await createPatchFromSqlFiles({ target_id: selectedTargetId, source_files: [...selectedFiles] });
      return triggerRun({ target_id: selectedTargetId, patch_id: patch.id });
    },
    onSuccess: (run) => {
      setRunId(run.id);
      void queryClient.setQueryData(runKeys.detail(run.id), run);
      void queryClient.invalidateQueries({ queryKey: runKeys.all });
    }
  });

  const stream = useRunStream(runId, runQuery.data, {
    enabled: Boolean(runId && !terminalStatuses.has(runQuery.data?.status ?? "pending")),
    onRunComplete: () => {
      if (runId) void queryClient.invalidateQueries({ queryKey: runKeys.detail(runId) });
      goToStep(3);
    },
    onRunFailed: () => {
      if (runId) void queryClient.invalidateQueries({ queryKey: runKeys.detail(runId) });
      goToStep(3);
    }
  });

  const terminal = terminalStatuses.has(stream.runStatus);
  const postcheckQuery = useQuery({
    queryKey: ["validation", runId ?? "none"],
    queryFn: () => getValidation(runId as string),
    enabled: Boolean(runId && terminal),
    retry: 5,
    retryDelay: 500
  });
  useEffect(() => {
    if (postcheckQuery.data) setPostcheck(postcheckQuery.data);
  }, [postcheckQuery.data]);

  const recheck = useMutation({
    mutationFn: () => rerunValidation(runId as string),
    onSuccess: (result) => setPostcheck(result)
  });

  const resetValidation = validation.reset;
  const resetExecution = execution.reset;
  const resetRecheck = recheck.reset;

  useEffect(() => {
    initializedTarget.current = undefined;
    resetSteps();
    setSelectedFiles(new Set());
    setPrecheck(undefined);
    setRunId(undefined);
    setPostcheck(undefined);
    resetValidation();
    resetExecution();
    resetRecheck();
  }, [resetExecution, resetRecheck, resetSteps, resetValidation, selectedTargetId]);

  useEffect(() => {
    if (!selectedTargetId || !objectsQuery.data || initializedTarget.current === selectedTargetId) return;
    initializedTarget.current = selectedTargetId;
    setSelectedFiles(new Set(objectsQuery.data.objects.map((item) => item.source_file)));
  }, [objectsQuery.data, selectedTargetId]);

  const objects = useMemo(() => objectsQuery.data?.objects ?? [], [objectsQuery.data?.objects]);
  const groups = useMemo(() => groupFiles(objects), [objects]);
  const selectedObjects = objects.filter((item) => selectedFiles.has(item.source_file));
  const currentRun = useMemo<Run | undefined>(() => {
    if (!runQuery.data) return undefined;
    return { ...runQuery.data, status: stream.runStatus, scripts: stream.orderedScripts };
  }, [runQuery.data, stream.orderedScripts, stream.runStatus]);
  const errorMessage = mutationError(validation.error ?? execution.error ?? recheck.error);
  const canAdvanceFromPrecheck = precheck !== undefined && precheck.status !== "errors";

  return (
    <section className="page schema-upgrade-page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Guided operation</span>
          <h1>Schema Upgrade</h1>
          <p>Select, validate, execute, and verify repository SQL in one guarded flow.</p>
        </div>
        <label className="upgrade-target-picker">
          <span>Target</span>
          <select value={selectedTargetId ?? ""} onChange={(event) => resetForTarget(event.target.value)}>
            <option value="">Select a target</option>
            {(targets.data ?? []).map((target) => (
              <option key={target.id} value={target.id}>
                {target.label}
              </option>
            ))}
          </select>
        </label>
      </header>

      <Stepper steps={steps} currentIndex={step} label="Upgrade progress" />

      {errorMessage ? (
        <div className="status-banner status-banner--error" role="alert">
          {errorMessage}
        </div>
      ) : null}

      {step === 0 ? (
        <section className="upgrade-pane">
          <div className="section-block__header">
            <div>
              <h2>Choose repository files</h2>
              <p>Selection is carried into validation and patch creation.</p>
            </div>
            <div className="row-actions">
              <button
                className="button button--secondary"
                type="button"
                onClick={() => updateSelectedFiles(new Set(objects.map((item) => item.source_file)))}
              >
                Select all
              </button>
              <button className="button button--ghost" type="button" onClick={() => updateSelectedFiles(new Set())}>
                Clear
              </button>
            </div>
          </div>
          {objectsQuery.isLoading ? <div className="empty-state">Loading schema objects...</div> : null}
          {objectsQuery.error ? (
            <div className="status-banner status-banner--error">Schema objects could not be loaded.</div>
          ) : null}
          {selectedTargetId && !objectsQuery.isLoading && groups.length === 0 ? (
            <div className="empty-state">No schema objects were discovered for this target.</div>
          ) : null}
          <ObjectTree
            groups={groups}
            selectedFiles={selectedFiles}
            invalid={precheck?.invalid.map((item) => item.name) ?? []}
            onToggle={(path) => updateSelectedFiles(toggleSelection(selectedFiles, path))}
          />
          <div className="upgrade-actions">
            <span>
              {selectedFiles.size} files · {selectedObjects.length} objects selected
            </span>
            <button
              className="button button--primary"
              type="button"
              disabled={selectedFiles.size === 0}
              onClick={() => nextStep(selectedFiles.size > 0)}
            >
              Continue to pre-check
            </button>
          </div>
        </section>
      ) : null}

      {step === 1 ? (
        <section className="upgrade-pane">
          <div className="section-block__header">
            <div>
              <h2>Validate synced SQL</h2>
              <p>The complete manifest is checked so cross-file dependencies and constraints remain safe.</p>
            </div>
            {precheck ? <StatusBadge status={precheck.status} /> : null}
          </div>
          <button
            className="button button--primary"
            type="button"
            disabled={!canMutate || validation.isPending}
            onClick={() => validation.mutate()}
          >
            <Icon source={CheckCircleIcon} size={16} />
            {validation.isPending ? "Validating" : precheck ? "Validate again" : "Run pre-check"}
          </button>
          {precheck ? (
            <ValidationTable result={precheck} />
          ) : (
            <div className="empty-state">Run the pre-check to continue.</div>
          )}
          {precheck?.status === "errors" ? (
            <div className="status-banner status-banner--error">
              <Icon source={WarningIcon} size={20} /> Resolve validation errors before executing.
            </div>
          ) : null}
          <div className="upgrade-actions">
            <button className="button button--secondary" type="button" onClick={previousStep}>
              Back
            </button>
            <button
              className="button button--primary"
              type="button"
              disabled={!canAdvanceFromPrecheck}
              onClick={() => nextStep(canAdvanceFromPrecheck)}
            >
              Continue to execute
            </button>
          </div>
        </section>
      ) : null}

      {step === 2 ? (
        <section className="upgrade-pane">
          <div className="section-block__header">
            <div>
              <h2>Execute checked files</h2>
              <p>The repository state is checked again immediately before patch creation.</p>
            </div>
            {runId ? <StatusBadge status={stream.runStatus} /> : null}
          </div>
          <div
            className={
              repoQuery.data?.drift || !repoQuery.data?.last_synced_commit
                ? "status-banner status-banner--warning"
                : "status-banner status-banner--ok"
            }
          >
            <Icon source={GitBranchIcon} size={20} />
            {repoQuery.data?.drift
              ? "Remote changes detected. Sync SQL files before executing."
              : repoQuery.data?.last_synced_commit
                ? `Ready at commit ${repoQuery.data.last_synced_commit.slice(0, 12)}.`
                : "Repository has not been synced."}
          </div>
          {!runId ? (
            <button
              className="button button--primary"
              type="button"
              disabled={
                !canMutate || execution.isPending || repoQuery.data?.drift || !repoQuery.data?.last_synced_commit
              }
              onClick={() => execution.mutate()}
            >
              {execution.isPending ? "Preparing run" : `Execute ${selectedFiles.size} files`}
            </button>
          ) : (
            <>
              <section className="section-block">
                <h2>Script progress</h2>
                <ScriptProgressList scripts={stream.orderedScripts} />
              </section>
              <section className="section-block">
                <h2>Log stream</h2>
                <LogViewer lines={stream.logLines} />
              </section>
            </>
          )}
          <div className="upgrade-actions">
            <button className="button button--secondary" type="button" disabled={Boolean(runId)} onClick={previousStep}>
              Back
            </button>
            <button
              className="button button--primary"
              type="button"
              disabled={!terminal}
              onClick={() => nextStep(terminal)}
            >
              View summary
            </button>
          </div>
        </section>
      ) : null}

      {step === 3 && precheck && currentRun ? (
        <section className="upgrade-pane">
          <div className="section-block__header">
            <div>
              <h2>Upgrade summary</h2>
              <p>Execution and post-run validation for run {currentRun.id}.</p>
            </div>
            <button
              className="button button--secondary"
              type="button"
              disabled={!canMutate || recheck.isPending}
              onClick={() => recheck.mutate()}
            >
              <Icon source={ArrowClockwiseIcon} size={16} />
              {recheck.isPending ? "Re-checking" : "Run post-check"}
            </button>
          </div>
          <UpgradeSummary
            objects={objects}
            selectedFiles={[...selectedFiles]}
            precheck={precheck}
            run={currentRun}
            postcheck={postcheck}
          />
        </section>
      ) : null}
    </section>
  );
};

interface UpgradeFileListItem {
  folder: string;
  path: string;
  sourceFile: string;
  objectTypes: SchemaObjectListItem["object_type"][];
  statuses: SchemaObjectListItem["status"][];
}

const ObjectTree = ({
  groups,
  selectedFiles,
  invalid,
  onToggle
}: {
  groups: Array<{ folder: string; files: UpgradeFileListItem[] }>;
  selectedFiles: Set<string>;
  invalid: string[];
  onToggle: (path: string) => void;
}) => (
  <div className="sql-files-listing">
    {groups.map((group) => (
      <details className="sql-folder-group" key={group.folder} open>
        <summary className="sql-folder-summary">
          <span className="sql-folder-title">
            <Icon source={folderIconSource(group.folder)} size={16} />
            <strong>{group.folder}</strong>
          </span>
          <span className="file-count">{group.files.length} files</span>
        </summary>
        <ul className="sql-file-list">
          {group.files.map((item) => {
            const hasError = invalid.some((name) => item.path.toLowerCase().includes(name.toLowerCase()));
            return (
              <li
                className={
                  hasError ? "sql-file-item upgrade-object upgrade-object--invalid" : "sql-file-item upgrade-object"
                }
                key={item.sourceFile}
              >
                <label className="upgrade-object__label">
                  <input
                    className="upgrade-object__checkbox"
                    type="checkbox"
                    checked={selectedFiles.has(item.sourceFile)}
                    onChange={() => onToggle(item.sourceFile)}
                  />
                  <Icon
                    className="upgrade-object__icon"
                    source={folderIconSource(item.folder, item.objectTypes[0])}
                    size={16}
                  />
                  <code>{item.path}</code>
                  <span>{item.objectTypes.map((type) => type.replaceAll("_", " ")).join(", ")}</span>
                </label>
                <span className="row-actions">
                  {item.statuses.map((status) => (
                    <StatusBadge key={status} status={status} />
                  ))}
                </span>
              </li>
            );
          })}
        </ul>
      </details>
    ))}
  </div>
);

export const groupFiles = (objects: SchemaObjectListItem[]) => {
  const files = new Map<string, UpgradeFileListItem>();
  objects.forEach((item) => {
    const existing = files.get(item.source_file);
    files.set(item.source_file, {
      folder: item.folder,
      path: item.path,
      sourceFile: item.source_file,
      objectTypes: Array.from(new Set([...(existing?.objectTypes ?? []), item.object_type])),
      statuses: Array.from(new Set([...(existing?.statuses ?? []), item.status]))
    });
  });

  const groups = new Map<string, UpgradeFileListItem[]>();
  files.forEach((item) => groups.set(item.folder, [...(groups.get(item.folder) ?? []), item]));
  return Array.from(groups, ([folder, groupedFiles]) => ({ folder, files: groupedFiles })).sort((a, b) =>
    a.folder.localeCompare(b.folder)
  );
};
const toggleSelection = (selection: Set<string>, path: string) => {
  const next = new Set(selection);
  if (next.has(path)) next.delete(path);
  else next.add(path);
  return next;
};
const mutationError = (error: unknown): string | undefined =>
  error instanceof Error ? error.message : error ? "The operation failed." : undefined;
