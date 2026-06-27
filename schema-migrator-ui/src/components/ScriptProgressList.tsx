import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ScriptRun } from "../types";
import { ErrorDetail } from "./ErrorDetail";
import { StatusBadge } from "./StatusBadge";

interface ScriptProgressListProps {
  scripts: ScriptRun[];
}

export const ScriptProgressList = ({ scripts }: ScriptProgressListProps) => {
  const [expandedId, setExpandedId] = useState<string | null>(null);

  if (scripts.length === 0) {
    return <div className="empty-state">No script events have been recorded.</div>;
  }

  return (
    <div className="script-list">
      {scripts.map((script) => {
        const isExpanded = expandedId === script.script_id;
        const canExpand = Boolean(script.error);

        return (
          <div className="script-row" key={script.script_id}>
            <button
              className="icon-button script-row__expand"
              type="button"
              aria-label={canExpand ? `${isExpanded ? "Collapse" : "Expand"} ${script.filename}` : "No error detail"}
              aria-expanded={isExpanded}
              disabled={!canExpand}
              onClick={() => setExpandedId(isExpanded ? null : script.script_id)}
            >
              {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            </button>
            <div className="script-row__order">{script.order}</div>
            <div className="script-row__file">
              <strong>{script.filename}</strong>
              {script.error ? <span>{script.error.message}</span> : null}
            </div>
            <StatusBadge status={script.status} />
            <div className="script-row__duration">
              {script.duration_ms !== null && script.duration_ms !== undefined ? `${script.duration_ms} ms` : "-"}
            </div>
            {isExpanded && script.error ? (
              <div className="script-row__detail">
                <ErrorDetail error={script.error} />
              </div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
};
