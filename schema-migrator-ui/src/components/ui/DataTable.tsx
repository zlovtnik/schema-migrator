import { useMemo, useRef, useState, type ReactNode } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDownIcon } from "@phosphor-icons/react/dist/csr/ArrowDown";
import { ArrowUpIcon } from "@phosphor-icons/react/dist/csr/ArrowUp";
import { CaretDownIcon } from "@phosphor-icons/react/dist/csr/CaretDown";
import { CaretRightIcon } from "@phosphor-icons/react/dist/csr/CaretRight";
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

export interface DataTableGroup {
  id: string;
  label: ReactNode;
  sortLabel?: string | undefined;
}

export interface DataTableProps<T> {
  caption: string;
  columns: DataTableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  empty: ReactNode;
  getRowState?: (row: T) => DataTableRowState | undefined;
  groupBy?: (row: T) => DataTableGroup | string;
  groupSummary?: (rowCount: number) => ReactNode;
  toolbar?: ReactNode;
  virtualizeThreshold?: number;
}

type SortState = {
  columnId: string;
  direction: "ascending" | "descending";
};

type DataTableItem<T> =
  | {
      type: "group";
      group: DataTableGroup;
      rowCount: number;
    }
  | {
      type: "row";
      row: T;
    };

const defaultGroupSummary = (rowCount: number) => `${rowCount} ${rowCount === 1 ? "row" : "rows"}`;

export const DataTable = <T,>({
  caption,
  columns,
  rows,
  rowKey,
  empty,
  getRowState,
  groupBy,
  groupSummary = defaultGroupSummary,
  toolbar,
  virtualizeThreshold
}: DataTableProps<T>) => {
  const [sort, setSort] = useState<SortState | null>(null);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => new Set());
  const scrollRef = useRef<HTMLDivElement>(null);

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

  const tableItems = useMemo<DataTableItem<T>[]>(() => {
    if (!groupBy) {
      return sortedRows.map((row) => ({ type: "row", row }));
    }

    const groups = new Map<string, { group: DataTableGroup; rows: T[] }>();
    sortedRows.forEach((row) => {
      const result = groupBy(row);
      const group = typeof result === "string" ? { id: result, label: result, sortLabel: result } : result;
      const entry = groups.get(group.id);
      if (entry) {
        entry.rows.push(row);
        return;
      }
      groups.set(group.id, { group, rows: [row] });
    });

    return [...groups.values()]
      .sort((a, b) =>
        String(a.group.sortLabel ?? a.group.id).localeCompare(String(b.group.sortLabel ?? b.group.id), undefined, {
          numeric: true
        })
      )
      .flatMap<DataTableItem<T>>(({ group, rows: groupRows }) => [
        { type: "group", group, rowCount: groupRows.length },
        ...(collapsedGroups.has(group.id) ? [] : groupRows.map((row) => ({ type: "row" as const, row })))
      ]);
  }, [collapsedGroups, groupBy, sortedRows]);

  const shouldVirtualize = Boolean(virtualizeThreshold && tableItems.length > virtualizeThreshold);
  const virtualizer = useVirtualizer({
    count: tableItems.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: (index) => (tableItems[index]?.type === "group" ? 42 : 36),
    overscan: 8,
    enabled: shouldVirtualize
  });
  const virtualItems = shouldVirtualize ? virtualizer.getVirtualItems() : [];
  const renderedIndexes = shouldVirtualize
    ? virtualItems.map((item) => item.index)
    : tableItems.map((_, index) => index);
  const firstVirtualItem = virtualItems[0];
  const lastVirtualItem = virtualItems[virtualItems.length - 1];
  const topPadding = shouldVirtualize && firstVirtualItem ? firstVirtualItem.start : 0;
  const bottomPadding =
    shouldVirtualize && lastVirtualItem ? Math.max(0, virtualizer.getTotalSize() - lastVirtualItem.end) : 0;
  const measureVirtualRow = shouldVirtualize ? virtualizer.measureElement : undefined;

  const toggleGroup = (groupId: string) => {
    setCollapsedGroups((current) => {
      const next = new Set(current);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  };

  if (rows.length === 0 && !toolbar) {
    return <div className="empty-state">{empty}</div>;
  }

  return (
    <div className="table-panel">
      {toolbar ? <div className="table-toolbar">{toolbar}</div> : null}
      {rows.length === 0 ? (
        <div className="empty-state data-table__empty">{empty}</div>
      ) : (
        <div
          className={
            shouldVirtualize ? "table-panel__scroller table-panel__scroller--virtual" : "table-panel__scroller"
          }
          ref={scrollRef}
        >
          <table className="data-table">
            <caption className="sr-only">{caption}</caption>
            <thead>
              <tr>
                {columns.map((column) => {
                  const sorted = sort?.columnId === column.id ? sort.direction : undefined;
                  return (
                    <th aria-sort={sorted} className={column.className} key={column.id} scope="col">
                      {column.sortValue ? (
                        <button className="data-table__sort" type="button" onClick={() => toggleSort(column)}>
                          <span className="data-table__sort-content">
                            <span className="data-table__sort-label">{column.header}</span>
                            {sorted ? (
                              <Icon
                                className="data-table__sort-icon"
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
              {topPadding > 0 ? <DataTableSpacer columns={columns.length} height={topPadding} /> : null}
              {renderedIndexes.map((index) => {
                const item = tableItems[index];
                if (!item) {
                  return null;
                }
                if (item.type === "group") {
                  const expanded = !collapsedGroups.has(item.group.id);
                  const summary = groupSummary(item.rowCount);
                  return (
                    <tr
                      className="data-table__group-row"
                      data-index={shouldVirtualize ? index : undefined}
                      key={`group-${item.group.id}`}
                      ref={measureVirtualRow}
                    >
                      <th colSpan={columns.length} scope="colgroup">
                        <button
                          aria-expanded={expanded}
                          aria-label={
                            typeof summary === "string" || typeof summary === "number"
                              ? `${item.group.sortLabel ?? item.group.id} ${summary}`
                              : undefined
                          }
                          className="data-table__group-toggle"
                          type="button"
                          onClick={() => toggleGroup(item.group.id)}
                        >
                          <Icon source={expanded ? CaretDownIcon : CaretRightIcon} size={16} weight="bold" />
                          <span>{item.group.label}</span>
                          <strong>{summary}</strong>
                        </button>
                      </th>
                    </tr>
                  );
                }

                const rowState = getRowState?.(item.row);
                return (
                  <tr
                    className={rowState?.className}
                    data-index={shouldVirtualize ? index : undefined}
                    data-selected={rowState?.selected ? "true" : undefined}
                    key={rowKey(item.row)}
                    ref={measureVirtualRow}
                  >
                    {columns.map((column) => (
                      <td className={column.className} key={column.id}>
                        {column.cell(item.row)}
                      </td>
                    ))}
                  </tr>
                );
              })}
              {bottomPadding > 0 ? <DataTableSpacer columns={columns.length} height={bottomPadding} /> : null}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

const DataTableSpacer = ({ columns, height }: { columns: number; height: number }) => (
  <tr aria-hidden="true" className="data-table__spacer">
    <td colSpan={columns} style={{ height }} />
  </tr>
);
