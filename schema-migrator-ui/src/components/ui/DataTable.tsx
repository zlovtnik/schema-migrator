import { useMemo, useState, type ReactNode } from "react";
import { ArrowDownIcon } from "@phosphor-icons/react/dist/csr/ArrowDown";
import { ArrowUpIcon } from "@phosphor-icons/react/dist/csr/ArrowUp";
import { Icon } from "./Icon";

export interface DataTableColumn<T> {
  id: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  sortValue?: (row: T) => string | number | undefined;
  className?: string;
}

export interface DataTableRowState {
  className?: string | undefined;
  selected?: boolean | undefined;
}

interface DataTableProps<T> {
  caption: string;
  columns: DataTableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  empty: ReactNode;
  getRowState?: (row: T) => DataTableRowState | undefined;
}

type SortState = {
  columnId: string;
  direction: "ascending" | "descending";
};

export const DataTable = <T,>({ caption, columns, rows, rowKey, empty, getRowState }: DataTableProps<T>) => {
  const [sort, setSort] = useState<SortState | null>(null);

  const sortedRows = useMemo(() => {
    if (!sort) {
      return rows;
    }
    const column = columns.find((item) => item.id === sort.columnId);
    if (!column?.sortValue) {
      return rows;
    }
    const multiplier = sort.direction === "ascending" ? 1 : -1;
    return [...rows].sort((a, b) => {
      const left = column.sortValue?.(a);
      const right = column.sortValue?.(b);
      return String(left ?? "").localeCompare(String(right ?? ""), undefined, { numeric: true }) * multiplier;
    });
  }, [columns, rows, sort]);

  const toggleSort = (column: DataTableColumn<T>) => {
    if (!column.sortValue) {
      return;
    }
    setSort((current) => ({
      columnId: column.id,
      direction: current?.columnId === column.id && current.direction === "ascending" ? "descending" : "ascending"
    }));
  };

  if (rows.length === 0) {
    return <div className="empty-state">{empty}</div>;
  }

  return (
    <div className="table-panel">
      <table className="data-table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => {
              const sorted = sort?.columnId === column.id ? sort.direction : undefined;
              return (
                <th aria-sort={sorted} className={column.className} key={column.id} scope="col">
                  {column.sortValue ? (
                    <button className="table-sort-button" type="button" onClick={() => toggleSort(column)}>
                      <span className="table-sort-button__content">
                        <span className="table-sort-button__label">{column.header}</span>
                        {sorted ? (
                          <Icon
                            className="table-sort-button__icon"
                            source={sorted === "ascending" ? ArrowUpIcon : ArrowDownIcon}
                            size={16}
                            weight="bold"
                          />
                        ) : null}
                      </span>
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sortedRows.map((row) => {
            const rowState = getRowState?.(row);
            return (
              <tr className={rowState?.className} data-selected={rowState?.selected ? "true" : undefined} key={rowKey(row)}>
                {columns.map((column) => (
                  <td className={column.className} key={column.id}>
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};
