import { ScriptProgressList } from "../../components/ScriptProgressList";
import { StatusBadge } from "../../components/StatusBadge";
import { ValidationTable } from "../../components/ValidationTable";
import type { Run, SchemaObjectListItem, SqlFilesValidationResult, ValidationResult } from "../../types";

interface UpgradeSummaryProps {
  objects: SchemaObjectListItem[];
  selectedFiles: string[];
  precheck: SqlFilesValidationResult;
  run: Run;
  postcheck?: ValidationResult | undefined;
}

export const UpgradeSummary = ({ objects, selectedFiles, precheck, run, postcheck }: UpgradeSummaryProps) => {
  const folders = new Set(objects.map((item) => item.folder)).size;
  const failures = run.scripts.filter((script) => script.status === "failed").length;

  return (
    <div className="upgrade-summary">
      <div className="upgrade-metrics" aria-label="Upgrade summary">
        <div className="summary-card">
          <span className="field-label">Catalog objects</span>
          <strong>{objects.length}</strong>
          <span>{folders} folders</span>
        </div>
        <div className="summary-card">
          <span className="field-label">Selected files</span>
          <strong>{selectedFiles.length}</strong>
          <span>Checked before execution</span>
        </div>
        <div className="summary-card">
          <span className="field-label">Pre-check</span>
          <StatusBadge status={precheck.status} />
          <span>{precheck.invalid.length} findings</span>
        </div>
        <div className="summary-card">
          <span className="field-label">Execution</span>
          <StatusBadge status={run.status} />
          <span>{failures} failed scripts</span>
        </div>
        <div className="summary-card">
          <span className="field-label">Post-check</span>
          {postcheck ? <StatusBadge status={postcheck.status} /> : <strong>Pending</strong>}
          <span>{postcheck ? `${postcheck.invalid.length} findings` : "Awaiting validation"}</span>
        </div>
      </div>

      <section className="section-block">
        <h2>Script results</h2>
        <ScriptProgressList scripts={run.scripts} />
      </section>

      {postcheck ? (
        <section className="section-block">
          <div className="section-block__header">
            <div>
              <h2>Post-upgrade validation</h2>
              <p>Checked {new Date(postcheck.checked_at).toLocaleString()}.</p>
            </div>
            <StatusBadge status={postcheck.status} />
          </div>
          <ValidationTable result={postcheck} />
        </section>
      ) : null}
    </div>
  );
};
