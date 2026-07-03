import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { LightningIcon } from "@phosphor-icons/react/dist/csr/Lightning";
import { PlusIcon } from "@phosphor-icons/react/dist/csr/Plus";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { ConnectionForm } from "../../components/ConnectionForm";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { useRuns } from "../../hooks/useRuns";
import { useCreateTarget, useDeleteTarget, useTargets, useTestConnection } from "../../hooks/useTargets";
import type { ConnectionTestResult, Target, TargetFormValues } from "../../types";

const redactedJdbcUrl = (value: string) =>
  value
    .replace(/(password=)[^&;\s]+/gi, "$1<redacted>")
    .replace(/(jdbc:oracle:thin:[^/\s:@]+\/)[^@\s]+(@)/gi, "$1<redacted>$2")
    .replace(/(\/\/[^:/?#]+:)[^@/?#]+(@)/gi, "$1<redacted>$2");

export const TargetListPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: targets = [], isLoading, error } = useTargets();
  const {
    data: runs = [],
    isLoading: runsLoading,
    isSuccess: runsLoaded,
    isError: runsError,
    isFetching: runsFetching,
    refetch: refetchRuns
  } = useRuns();
  const createTarget = useCreateTarget();
  const deleteTarget = useDeleteTarget();
  const testConnection = useTestConnection();
  const [createOpen, setCreateOpen] = useState(false);
  const [targetToDelete, setTargetToDelete] = useState<Target | null>(null);
  const [testingTargetId, setTestingTargetId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, ConnectionTestResult>>({});
  const [preSaveTestResult, setPreSaveTestResult] = useState<ConnectionTestResult | undefined>();
  const preSaveTestRequestRef = useRef(0);

  const activeRunTargetIds = useMemo(
    () => new Set(runs.filter((run) => run.status === "running" || run.status === "pending").map((run) => run.target_id)),
    [runs]
  );

  const closeCreate = () => {
    preSaveTestRequestRef.current += 1;
    setCreateOpen(false);
    setPreSaveTestResult(undefined);
    if (searchParams.has("create")) {
      const next = new URLSearchParams(searchParams);
      next.delete("create");
      setSearchParams(next, { replace: true });
    }
  };

  const clearPreSaveTestResult = () => {
    preSaveTestRequestRef.current += 1;
    setPreSaveTestResult(undefined);
  };

  const create = (values: TargetFormValues) => {
    createTarget.mutate(values, {
      onSuccess: closeCreate
    });
  };

  useEffect(() => {
    if (searchParams.get("create") === "1") {
      setCreateOpen(true);
    }
  }, [searchParams]);

  useEffect(() => {
    if (!createOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        closeCreate();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [createOpen]);

  const runConnectionTest = (target: Target) => {
    setTestingTargetId(target.id);
    testConnection.mutate(
      { id: target.id },
      {
        onSuccess: (result) => setTestResults((previous) => ({ ...previous, [target.id]: result })),
        onSettled: () => setTestingTargetId(null)
      }
    );
  };

  const testPreSave = async (values: TargetFormValues) => {
    const requestId = preSaveTestRequestRef.current + 1;
    preSaveTestRequestRef.current = requestId;
    const result = await testConnection.mutateAsync({ values });
    if (preSaveTestRequestRef.current === requestId) {
      setPreSaveTestResult(result);
    }
    return result;
  };

  const confirmDelete = () => {
    if (!targetToDelete || runsError || !runsLoaded || runsLoading || activeRunTargetIds.has(targetToDelete.id)) {
      return;
    }
    deleteTarget.mutate(targetToDelete.id, {
      onSuccess: () => setTargetToDelete(null)
    });
  };

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Targets</span>
          <h1>Database targets</h1>
        </div>
        <button className="button button--primary" type="button" onClick={() => setCreateOpen(true)}>
          <Icon source={PlusIcon} size={16} weight="bold" />
          Create
        </button>
      </header>

      {error ? <div className="status-banner status-banner--error">Unable to load targets.</div> : null}
      {runsError ? (
        <div className="status-banner status-banner--error">
          Unable to load run state.
          <button className="link-button" type="button" onClick={() => void refetchRuns()} disabled={runsFetching}>
            {runsFetching ? "Retrying" : "Retry"}
          </button>
        </div>
      ) : null}
      {isLoading ? <div className="empty-state">Loading targets...</div> : null}

      {!isLoading && targets.length === 0 ? (
        <div className="empty-state">No targets configured. Add one to get started.</div>
      ) : null}

      {targets.length > 0 ? (
        <div className="table-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Label</th>
                <th scope="col">App</th>
                <th scope="col">Env</th>
                <th scope="col">Database URL</th>
                <th scope="col">Status</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {targets.map((target) => {
                const result = testResults[target.id];
                const status = result ? (result.ok ? "connected" : "error") : "untested";
                const hasActiveRuns = activeRunTargetIds.has(target.id);
                const runsUnavailable = runsLoading || (!runsLoaded && !runsError);
                const deleteDisabled = runsUnavailable || runsError || hasActiveRuns;
                return (
                  <tr key={target.id}>
                    <td>
                      <Link to={target.id}>{target.label}</Link>
                    </td>
                    <td>{target.app_name}</td>
                    <td>
                      <StatusBadge status={target.env === "production" ? "warning" : "clean"} title={target.env} />
                      <span className="cell-subtle">{target.env}</span>
                    </td>
                    <td>
                      <code>{redactedJdbcUrl(target.jdbc_url)}</code>
                    </td>
                    <td>
                      <StatusBadge
                        status={status}
                        title={result?.ok ? `${result.latency_ms ?? 0} ms` : result?.error}
                      />
                    </td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="button button--secondary button--small"
                          type="button"
                          onClick={() => runConnectionTest(target)}
                          disabled={testingTargetId === target.id}
                        >
                          <Icon source={LightningIcon} size={16} />
                          {testingTargetId === target.id ? "Testing" : "Test"}
                        </button>
                        <button
                          className="icon-button icon-button--danger"
                          type="button"
                          aria-label={`Delete ${target.label}`}
                          onClick={() => {
                            if (!deleteDisabled) {
                              setTargetToDelete(target);
                            }
                          }}
                          disabled={deleteDisabled}
                          title={
                            runsError
                              ? "Delete disabled until run state reloads"
                              : runsUnavailable
                              ? "Delete disabled until run state loads"
                              : hasActiveRuns
                                ? "Delete disabled while active runs exist"
                                : undefined
                          }
                        >
                          <Icon source={TrashIcon} size={16} label={`Delete ${target.label}`} />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {createOpen ? (
        <aside className="slide-over" role="dialog" aria-modal="true" aria-labelledby="create-target-title">
          <div className="slide-over__panel">
            <div className="slide-over__header">
              <h2 id="create-target-title">Create target</h2>
              <button className="button button--ghost button--small" type="button" onClick={closeCreate}>
                Close
              </button>
            </div>
            <ConnectionForm
              submitting={createTarget.isPending}
              testing={testConnection.isPending}
              testResult={preSaveTestResult}
              onSubmit={create}
              onCancel={closeCreate}
              onTest={testPreSave}
              onCredentialsChange={clearPreSaveTestResult}
            />
          </div>
        </aside>
      ) : null}

      <ConfirmDialog
        open={Boolean(targetToDelete)}
        title="Delete target"
        message={`Delete ${targetToDelete?.label ?? "this target"}? This cannot be undone.`}
        confirmLabel="Delete"
        destructive
        busy={deleteTarget.isPending}
        requireText={targetToDelete?.env === "production" ? targetToDelete.label : undefined}
        onCancel={() => setTargetToDelete(null)}
        onConfirm={confirmDelete}
      />
    </section>
  );
};
