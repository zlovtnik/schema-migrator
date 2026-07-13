import { useMemo, useState } from "react";
import { DownloadIcon } from "@phosphor-icons/react/dist/csr/Download";
import { objectTypeOptions, type InvalidObject, type ObjectType } from "../types";
import { StatusBadge } from "./StatusBadge";
import { DataTable, type DataTableColumn } from "./ui/DataTable";
import { Icon } from "./ui/Icon";

interface ValidationTableProps {
  result: {
    run_id?: string;
    target_id: string;
    invalid: InvalidObject[];
  };
}

type ValidationRow = InvalidObject & { key: string };

const csvNeutralize = (value: string) => (/^[=+\-@]/.test(value) ? `'${value}` : value);
const csvEscape = (value: string) => `"${csvNeutralize(value).replace(/"/g, '""')}"`;

export const ValidationTable = ({ result }: ValidationTableProps) => {
  const [filter, setFilter] = useState<ObjectType | "all">("all");
  const [expandedKey, setExpandedKey] = useState<string | null>(null);

  const rows = useMemo(() => {
    return result.invalid
      .map<ValidationRow>((row, index) => ({
        ...row,
        key: `${row.object_type}:${row.schema}:${row.name}:${row.severity}:${row.error}:${index}`
      }))
      .filter((row) => filter === "all" || row.object_type === filter);
  }, [filter, result.invalid]);

  const columns: DataTableColumn<ValidationRow>[] = [
    { id: "object_type", header: "Object type", sortValue: (row) => row.object_type, cell: (row) => row.object_type },
    { id: "schema", header: "Schema", sortValue: (row) => row.schema, cell: (row) => row.schema },
    { id: "name", header: "Name", sortValue: (row) => row.name, cell: (row) => row.name },
    {
      id: "error",
      header: "Error",
      cell: (row) => {
        const isExpanded = expandedKey === row.key;
        return (
          <>
            <button
              className="link-button"
              type="button"
              aria-expanded={isExpanded}
              onClick={() => setExpandedKey(isExpanded ? null : row.key)}
            >
              {isExpanded ? row.error : `${row.error.slice(0, 96)}${row.error.length > 96 ? "..." : ""}`}
            </button>
            {isExpanded ? <pre className="expanded-error">{row.error}</pre> : null}
          </>
        );
      }
    },
    {
      id: "severity",
      header: "Severity",
      sortValue: (row) => row.severity,
      cell: (row) => <StatusBadge status={row.severity} />
    }
  ];

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
    link.download = `validation-${result.run_id ?? result.target_id}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <DataTable
      caption="Invalid schema objects"
      columns={columns}
      rows={rows}
      rowKey={(row) => row.key}
      empty="No invalid objects match this filter."
      toolbar={
        <>
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
        </>
      }
    />
  );
};
