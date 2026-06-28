import { useParams } from "react-router-dom";
import { ArrowClockwiseIcon } from "@phosphor-icons/react/dist/csr/ArrowClockwise";
import { StatusBadge } from "../../components/StatusBadge";
import { Icon } from "../../components/ui/Icon";
import { ValidationTable } from "../../components/ValidationTable";
import { useRerunValidation, useValidation } from "../../hooks/useValidation";

export const ValidationReportPage = () => {
  const { runId } = useParams();
  const { data: result, isLoading, error } = useValidation(runId);
  const rerun = useRerunValidation(runId ?? "");

  if (runId === "latest") {
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
          <button className="button button--secondary" type="button" onClick={() => rerun.mutate()} disabled={rerun.isPending}>
            <Icon source={ArrowClockwiseIcon} size={16} />
            {rerun.isPending ? "Re-validating" : "Re-validate"}
          </button>
        </div>
      </header>

      <div className={blockingErrors > 0 ? "status-banner status-banner--error" : "status-banner status-banner--ok"}>
        {blockingErrors > 0
          ? `${blockingErrors} invalid object errors block marking this patch as fully applied.`
          : "No blocking validation errors."}
      </div>

      <ValidationTable result={result} />
    </section>
  );
};
