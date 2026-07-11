import { useMemo, useState } from "react";
import { DownloadIcon } from "@phosphor-icons/react/dist/csr/Download";
import { objectTypeOptions, type InvalidObject, type ObjectType, type ValidationResult } from "../types";
import { StatusBadge } from "./StatusBadge";
import { Icon } from "./ui/Icon";

interface ValidationTableProps {
  result: ValidationResult;
}

type ValidationRow = InvalidObject & { key: string };

const csvNeutralize = (value: string) => (/^[=+\-@]/.test(value) ? `'${value}` : value);
const csvEscape = (value: string) => `"${csvNeutralize(value).replace(/"/g, '""')}"`;

export const ValidationTable = ({ result }: ValidationTableProps) => {
  const [filter, setFilter] = useState<ObjectType | "all">("all");
  const [expandedKey, setExpandedKey] = useState<string | null>(null);
  const [sortKey, setSortKey] = useState<keyof InvalidObject>("severity");

  const rows = useMemo(() => {
    return result.invalid
      .map<ValidationRow>((row, index) => ({
        ...row,
        key: `${row.object_type}:${row.schema}:${row.name}:${row.severity}:${row.error}:${index}`
      }))
      .filter((row) => filter === "all" || row.object_type === filter)
      .sort((a, b) => String(a[sortKey]).localeCompare(String(b[sortKey])));
  }, [filter, result.invalid, sortKey]);

  const exportCsv = () => {
    const header = ["object_type", "schema", "name", "severity", "error"];
    const csv = [
      header.join(","),
      ...rows.map((row) =>
        [row.object_type, row.schema, row.name, row.severity, row.error].map((value) => csvEscape(value)).join(",")
      )
    ].join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `validation-${result.run_id}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="table-panel">
      <div className="table-toolbar">
        <label>
          Object type
          <select value={filter} onChange={(event) => setFilter(event.target.value as ObjectType | "all")}>
            <option value="all">All types</option>
            {objectTypeOptions.map((type) => (
              <option value={type} key={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
        <button className="button button--secondary" type="button" onClick={exportCsv}>
          <Icon source={DownloadIcon} size={16} />
          Export CSV
        </button>
      </div>
      {rows.length === 0 ? (
        <div className="empty-state">No invalid objects match this filter.</div>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th aria-sort={sortKey === "object_type" ? "ascending" : undefined} scope="col">
                <button type="button" onClick={() => setSortKey("object_type")}>
                  Object type
                </button>
              </th>
              <th aria-sort={sortKey === "schema" ? "ascending" : undefined} scope="col">
                <button type="button" onClick={() => setSortKey("schema")}>
                  Schema
                </button>
              </th>
              <th aria-sort={sortKey === "name" ? "ascending" : undefined} scope="col">
                <button type="button" onClick={() => setSortKey("name")}>
                  Name
                </button>
              </th>
              <th scope="col">Error</th>
              <th aria-sort={sortKey === "severity" ? "ascending" : undefined} scope="col">
                <button type="button" onClick={() => setSortKey("severity")}>
                  Severity
                </button>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const isExpanded = expandedKey === row.key;
              return (
                <tr key={row.key}>
                  <td>{row.object_type}</td>
                  <td>{row.schema}</td>
                  <td>{row.name}</td>
                  <td>
                    <button
                      className="link-button"
                      type="button"
                      aria-expanded={isExpanded}
                      onClick={() => setExpandedKey(isExpanded ? null : row.key)}
                    >
                      {isExpanded ? row.error : `${row.error.slice(0, 96)}${row.error.length > 96 ? "..." : ""}`}
                    </button>
                    {isExpanded ? <pre className="expanded-error">{row.error}</pre> : null}
                  </td>
                  <td>
                    <StatusBadge status={row.severity} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
};
