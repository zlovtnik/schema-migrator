import { StatusBadge } from "./StatusBadge";
import { DataTable, type DataTableColumn } from "./ui/DataTable";
import type { SnapshotDiffItem } from "../types";

interface SnapshotDiffTableProps {
  items: SnapshotDiffItem[];
  empty?: string | undefined;
}

const columns: DataTableColumn<SnapshotDiffItem>[] = [
  {
    id: "path",
    header: "Path",
    sortValue: (item) => item.path,
    cell: (item) => <code title={item.path}>{item.path}</code>
  },
  {
    id: "type",
    header: "Change",
    sortValue: (item) => item.diff_type,
    cell: (item) => <StatusBadge status={item.diff_type} />
  },
  {
    id: "before",
    header: "Base SHA-256",
    sortValue: (item) => item.before_sha256 ?? "",
    cell: (item) =>
      item.before_sha256 ? (
        <code title={item.before_sha256}>{item.before_sha256}</code>
      ) : (
        <span className="cell-subtle">None</span>
      )
  },
  {
    id: "after",
    header: "Compare SHA-256",
    sortValue: (item) => item.after_sha256 ?? "",
    cell: (item) =>
      item.after_sha256 ? (
        <code title={item.after_sha256}>{item.after_sha256}</code>
      ) : (
        <span className="cell-subtle">None</span>
      )
  }
];

export const SnapshotDiffTable = ({ items, empty = "No file changes detected." }: SnapshotDiffTableProps) => (
  <DataTable
    caption="Snapshot diff"
    columns={columns}
    rows={items}
    rowKey={(item) => `${item.diff_type}:${item.path}`}
    getRowState={(item) => ({ className: `row--${item.diff_type}` })}
    empty={empty}
  />
);
