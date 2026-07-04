import { useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { ActivityTable } from "../../components/ActivityTable";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { ConnectionForm } from "../../components/ConnectionForm";
import { Icon } from "../../components/ui/Icon";
import { useAuditEvents } from "../../hooks/useAudit";
import { useRuns } from "../../hooks/useRuns";
import { useSession } from "../../hooks/useSession";
import { useDeleteTarget, useTarget, useTestConnection, useUpdateTarget } from "../../hooks/useTargets";
import type { ConnectionTestResult, TargetFormValues } from "../../types";

export const TargetFormPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { canManageTargets, canViewAudit } = useSession();
  const { data: target, isLoading, error } = useTarget(id);
  const updateTarget = useUpdateTarget(id ?? "");
  const deleteTarget = useDeleteTarget();
  const testConnection = useTestConnection();
  const { data: runs = [], isLoading: runsLoading, isSuccess: runsLoaded } = useRuns(id);
  const { data: activity = [], isLoading: activityLoading } = useAuditEvents(
    { entity_type: "target", entity_id: id ?? null },
    canViewAudit && Boolean(id)
  );
  const [testResult, setTestResult] = useState<ConnectionTestResult | undefined>();
  const testRequestRef = useRef(0);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const hasActiveRuns = runs.some((run) => run.status === "running" || run.status === "pending");
  const deleteDisabled = !canManageTargets || !runsLoaded || runsLoading || hasActiveRuns;
  const returnToList = () => navigate("..", { relative: "path" });

  const submit = (values: TargetFormValues) => {
    if (!canManageTargets) {
      return;
    }
    updateTarget.mutate(values, {
      onSuccess: returnToList
    });
  };

  const test = async (values: TargetFormValues) => {
    const requestId = testRequestRef.current + 1;
    testRequestRef.current = requestId;
    const useFormValues = Boolean(values.password?.trim()) || values.jdbc_url.trim() !== target?.jdbc_url.trim();
    const result = await testConnection.mutateAsync(useFormValues ? { values } : { id });
    if (testRequestRef.current === requestId) {
      setTestResult(result);
    }
    return result;
  };

  const clearTestResult = () => {
    testRequestRef.current += 1;
    setTestResult(undefined);
  };

  const confirmDelete = () => {
    if (!canManageTargets || !id || deleteDisabled) {
      return;
    }
    deleteTarget.mutate(id, {
      onSuccess: returnToList
    });
  };

  if (isLoading) {
    return <div className="page empty-state">Loading target...</div>;
  }

  if (error || !target) {
    return <div className="page status-banner status-banner--error">Target could not be loaded.</div>;
  }

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Target detail</span>
          <h1>{target.label}</h1>
        </div>
        <button
          className="button button--danger"
          type="button"
          disabled={deleteDisabled}
          title={
            !canManageTargets
              ? "Admin role required to delete targets"
              : !runsLoaded || runsLoading
              ? "Delete disabled until run state loads"
              : hasActiveRuns
                ? "Delete disabled while active runs exist"
                : undefined
          }
          onClick={() => setConfirmOpen(true)}
        >
          <Icon source={TrashIcon} size={16} />
          Delete
        </button>
      </header>

      <ConnectionForm
        initialTarget={target}
        submitting={updateTarget.isPending}
        testing={testConnection.isPending}
        testResult={testResult}
        onSubmit={submit}
        onCancel={returnToList}
        onTest={test}
        onCredentialsChange={clearTestResult}
        readOnly={!canManageTargets}
        readOnlyReason="Admin role required to edit target credentials."
      />

      {canViewAudit ? (
        <section className="section-block">
          <h2>Activity</h2>
          {activityLoading ? (
            <div className="empty-state">Loading activity...</div>
          ) : (
            <ActivityTable events={activity} empty="No audit events recorded for this target." />
          )}
        </section>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title="Delete target"
        message={`Delete ${target.label}? This cannot be undone.`}
        confirmLabel="Delete"
        destructive
        busy={deleteTarget.isPending}
        requireText={target.env === "production" ? target.label : undefined}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={confirmDelete}
      />
    </section>
  );
};
