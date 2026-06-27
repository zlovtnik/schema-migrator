import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { ConnectionForm } from "../../components/ConnectionForm";
import { Icon } from "../../components/ui/Icon";
import { useRuns } from "../../hooks/useRuns";
import { useDeleteTarget, useTarget, useTestConnection, useUpdateTarget } from "../../hooks/useTargets";
import type { ConnectionTestResult, TargetFormValues } from "../../types";

export const TargetFormPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: target, isLoading, error } = useTarget(id);
  const updateTarget = useUpdateTarget(id ?? "");
  const deleteTarget = useDeleteTarget();
  const testConnection = useTestConnection();
  const { data: runs = [] } = useRuns();
  const [testResult, setTestResult] = useState<ConnectionTestResult | undefined>();
  const [confirmOpen, setConfirmOpen] = useState(false);

  const hasActiveRuns = runs.some((run) => run.target_id === id && (run.status === "running" || run.status === "pending"));

  const submit = (values: TargetFormValues) => {
    updateTarget.mutate(values, {
      onSuccess: () => navigate("/targets")
    });
  };

  const test = async (values: TargetFormValues) => {
    const result = await testConnection.mutateAsync({ id, values });
    setTestResult(result);
    return result;
  };

  const confirmDelete = () => {
    if (!id) {
      return;
    }
    if (runs.some((run) => run.target_id === id && (run.status === "running" || run.status === "pending"))) {
      return;
    }
    deleteTarget.mutate(id, {
      onSuccess: () => navigate("/targets")
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
          disabled={hasActiveRuns}
          title={hasActiveRuns ? "Delete disabled while active runs exist" : undefined}
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
        onCancel={() => navigate("/targets")}
        onTest={test}
      />

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
