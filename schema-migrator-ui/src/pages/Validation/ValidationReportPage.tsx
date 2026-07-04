import { Navigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ArrowClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowClockwise";
import { listRuns } from "../../api/runs";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { useMutationGuard } from "../../hooks/useMutationGuard";
import { ValidationTable } from "../../components/ValidationTable";
import { runKeys } from "../../hooks/useRuns";
import { useSession } from "../../hooks/useSession";
import { useRerunValidation, useValidation } from "../../hooks/useValidation";

export const ValidationReportPage = () => {
  const { runId } = useParams();
  const { canMutate } = useSession();
  const mutationGuard = useMutationGuard(canMutate);
  const validationRunId = runId === "latest" ? undefined : runId;
  const runsQuery = useQuery({
    queryKey: runKeys.list(),
    queryFn: () => listRuns(),
    enabled: runId === "latest"
  });
  const { data: result, isLoading, error } = useValidation(validationRunId);
  const rerun = useRerunValidation(validationRunId ?? "");

  if (runId === "latest") {
    if (runsQuery.isLoading) {
      return <div className="page empty-state">Loading validation report...</div>;
    }

    const latestCompletedRun = runsQuery.data
      ?.filter((run) => run.status === "completed")
      .slice()
      .sort((a, b) => Date.parse(b.ended_at ?? b.started_at) - Date.parse(a.ended_at ?? a.started_at))[0];

    if (latestCompletedRun) {
      return <Navigate to={`/validation/${latestCompletedRun.id}`} replace />;
    }

    return (
      <section className="page">
        <div className="empty-state">Open a completed run to view its validation report.</div>
      </section>
    );
  }

  if (isLoading) {
    return <div className="page empty-state">Loading validation report...</div>;
  }

  if (error || !result || !runId) {
    return <div className="page status-banner status-banner--error">Validation report could not be loaded.</div>;
  }

  const blockingErrors = result.invalid.filter((row) => row.severity === "error").length;
  const rerunGuard = mutationGuard("Viewer role cannot re-run validation", rerun.isPending);

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Validation</span>
          <h1>Run {runId}</h1>
          <p>Checked {new Date(result.checked_at).toLocaleString()}</p>
        </div>
        <div className="row-actions">
          <StatusBadge status={result.status} />
          <button
            className="button button--secondary"
            type="button"
            onClick={() => rerun.mutate()}
            disabled={rerunGuard.disabled}
            title={rerunGuard.title}
          >
            <Icon source={ArrowClockwiseIcon} size={16} />
            {rerun.isPending ? "Re-validating" : "Re-validate"}
          </button>
        </div>
      </header>

      <div className={blockingErrors > 0 ? "status-banner status-banner--error" : "status-banner status-banner--ok"}>
        {blockingErrors > 0
          ? `${blockingErrors} invalid object errors block marking this migration as fully applied.`
          : "No blocking validation errors."}
      </div>

      <ValidationTable result={result} />
    </section>
  );
};
