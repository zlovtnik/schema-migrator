import { useCallback, useMemo, useState, type SyntheticEvent } from "react";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { PageHeader } from "../../components/PageHeader";
import { SqlPreviewPane } from "../../components/SqlPreviewPane";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useDrift } from "../../hooks/useSchema";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import type { DriftItem, DriftType, SchemaControlSummary } from "../../types";

type DriftFilter = DriftType | "all";

export const DriftPage = () => {
  const selectedTarget = useSelectedTargetId();
  const { data, isLoading, error } = useDrift(selectedTarget);
  const [textFilter, setTextFilter] = useState("");
  const [driftFilter, setDriftFilter] = useState<DriftFilter>("all");
  const [openKey, setOpenKey] = useState<string | null>(null);

  const textFilteredItems = useMemo(() => {
    const query = textFilter.trim().toLowerCase();
    return (data?.items ?? []).filter((item) => {
      return (
        !query ||
        item.name.toLowerCase().includes(query) ||
        item.schema.toLowerCase().includes(query) ||
        item.object_type.toLowerCase().includes(query) ||
        item.drift_type.toLowerCase().includes(query)
      );
    });
  }, [data?.items, textFilter]);

  const items = useMemo(() => {
    return textFilteredItems.filter((item) => driftFilter === "all" || item.drift_type === driftFilter);
  }, [driftFilter, textFilteredItems]);

  const driftCounts = useMemo(() => countByDriftType(textFilteredItems), [textFilteredItems]);

  const openDriftDetail = useCallback((item: DriftItem) => {
    const key = driftItemKey(item);
    setOpenKey(key);
    document.getElementById(driftDetailId(item))?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, []);

  const handleDetailToggle = (item: DriftItem, event: SyntheticEvent<HTMLDetailsElement>) => {
    const key = driftItemKey(item);
    if (event.currentTarget.open) {
      setOpenKey(key);
      return;
    }
    setOpenKey((current) => (current === key ? null : current));
  };

  const columns = useMemo<DataTableColumn<DriftItem>[]>(
    () => [
      {
        id: "object",
        header: "Object",
        sortValue: (item) => item.name,
        cell: (item) => {
          const key = driftItemKey(item);
          const isOpen = openKey === key;
          return (
            <button
              aria-controls={driftDetailId(item)}
              aria-expanded={isOpen}
              className={isOpen ? "link-button link-button--active" : "link-button"}
              type="button"
              onClick={() => openDriftDetail(item)}
            >
              <code>{item.name}</code>
            </button>
          );
        }
      },
      {
        id: "type",
        header: "Type",
        sortValue: (item) => item.object_type,
        cell: (item) => <span className="object-type-chip">{formatLabel(item.object_type)}</span>
      },
      {
        id: "schema",
        header: "Schema",
        sortValue: (item) => item.schema,
        cell: (item) => <code>{item.schema}</code>
      },
      {
        id: "drift",
        header: "Drift",
        sortValue: (item) => item.drift_type,
        cell: (item) => <StatusBadge status={item.drift_type} />
      },
      {
        id: "source",
        header: "Source",
        sortValue: (item) => item.source_file ?? "",
        cell: (item) => item.source_file ? <code>{item.source_file}</code> : <span className="cell-subtle">Live catalog</span>
      },
      {
        id: "detected",
        header: "Detected",
        sortValue: (item) => item.detected_at,
        cell: (item) => <time dateTime={item.detected_at}>{new Date(item.detected_at).toLocaleString()}</time>
      }
    ],
    [openDriftDetail, openKey]
  );

  return (
    <section className="page">
      <PageHeader
        eyebrow="Drift"
        title="Drift detection"
        description="Compare manifest-defined schema objects with the selected target catalog."
        actions={<TargetSelector />}
      />

      {!selectedTarget ? (
        <EmptyState icon={<Icon source={ShieldCheckIcon} size={24} />} title="Select a target">
          Choose a target to inspect drift between SQL definitions and live catalog state.
        </EmptyState>
      ) : null}

      {selectedTarget && isLoading ? <Skeleton rows={8} label="Loading drift results" /> : null}

      {selectedTarget && error ? (
        <div className="status-banner status-banner--error" role="alert">
          Drift results could not be loaded.
        </div>
      ) : null}

      {data?.warnings.length ? (
        <div className="status-banner" role="status">
          {data.warnings.join(" ")}
        </div>
      ) : null}

      {data && !data.supported ? (
        <EmptyState icon={<Icon source={ShieldCheckIcon} size={24} />} title="Drift feed unsupported">
          {data.db_kind === "oracle"
            ? "Oracle drift detection is limited to connection-level checks until an Oracle catalog target is available."
            : "This target does not expose drift data."}
        </EmptyState>
      ) : null}

      {data?.supported ? (
        <div className="drift-page-stack">
          <ControlSummaryPanel summary={data.control_summary} />
          {data.items.length === 0 ? (
            <EmptyState icon={<Icon source={ShieldCheckIcon} size={24} />} title="No drift detected">
              All returned objects match the available manifest and schema-control state.
            </EmptyState>
          ) : (
            <div className="drift-workspace">
              <label className="list-filter">
                Filter drift results
                <input
                  data-list-filter
                  name="drift-filter"
                  autoComplete="off"
                  value={textFilter}
                  onChange={(event) => setTextFilter(event.target.value)}
                />
              </label>
              <div className="drift-chip-row" aria-label="Drift type filters">
                <button
                  className={driftFilter === "all" ? "drift-chip drift-chip--active" : "drift-chip"}
                  type="button"
                  onClick={() => setDriftFilter("all")}
                  aria-pressed={driftFilter === "all"}
                >
                  <span>All drift</span>
                  <strong>{textFilteredItems.length}</strong>
                </button>
                {Array.from(driftCounts.entries()).map(([type, count]) => (
                  <button
                    className={driftFilter === type ? "drift-chip drift-chip--active" : "drift-chip"}
                    key={type}
                    type="button"
                    onClick={() => setDriftFilter(type)}
                    aria-pressed={driftFilter === type}
                  >
                    <StatusBadge status={type} />
                    <strong>{count}</strong>
                  </button>
                ))}
              </div>
              <DataTable
                caption="Drift results"
                columns={columns}
                rows={items}
                rowKey={driftItemKey}
                getRowState={(item) => ({ selected: driftItemKey(item) === openKey })}
                empty={textFilter ? `No drift results match "${textFilter}".` : "No drift detected."}
              />
              <section className="section-block">
                <h2>Drift details</h2>
                <div className="drift-detail-grid">
                  {items.map((item) => {
                    const key = driftItemKey(item);
                    const isOpen = openKey === key;
                    return (
                      <details
                        className="drift-detail"
                        id={driftDetailId(item)}
                        key={key}
                        open={isOpen}
                        onToggle={(event) => handleDetailToggle(item, event)}
                      >
                        <summary className="drift-detail__summary">
                          <span className="drift-detail__title">
                            <StatusBadge status={item.drift_type} />
                            <strong>
                              <code>{item.schema}.{item.name}</code>
                            </strong>
                          </span>
                          <span className="drift-detail__meta">{formatLabel(item.object_type)}</span>
                        </summary>
                        {isOpen ? (
                          <>
                            {item.apply_status ? (
                              <div className="drift-detail__control">
                                schema_control status <strong>{item.apply_status}</strong>
                              </div>
                            ) : null}
                            <div className="sql-preview-grid">
                              <SqlPreviewPane code={item.expected} title="Expected" />
                              <SqlPreviewPane code={item.actual} title="Actual" />
                            </div>
                          </>
                        ) : null}
                      </details>
                    );
                  })}
                </div>
              </section>
            </div>
          )}
        </div>
      ) : null}
    </section>
  );
};

const ControlSummaryPanel = ({ summary }: { summary: SchemaControlSummary | null | undefined }) => {
  if (!summary) {
    return null;
  }

  const counts = [
    ["Total", summary.total_count],
    ["Applied", summary.applied_count],
    ["Skipped", summary.skipped_count],
    ["Pending", summary.pending_count],
    ["Failed", summary.failed_count]
  ] as const;

  return (
    <section className="control-summary" aria-label="Schema control summary">
      <header className="control-summary__header">
        <div>
          <span className="eyebrow">Schema control</span>
          <h2>Control state</h2>
        </div>
        <StatusBadge status={summary.ready ? "clean" : "warnings"} />
      </header>
      <div className="control-summary__grid">
        {counts.map(([label, value]) => (
          <div className="control-summary__metric" key={label}>
            <span>{label}</span>
            <strong>{value}</strong>
          </div>
        ))}
      </div>
      <dl className="control-summary__timestamps">
        <div>
          <dt>Last applied</dt>
          <dd>{formatOptionalDate(summary.last_applied_at)}</dd>
        </div>
        <div>
          <dt>Last updated</dt>
          <dd>{formatOptionalDate(summary.last_updated_at)}</dd>
        </div>
      </dl>
      {summary.failed_objects.length ? (
        <div className="control-summary__failed">
          <span>Failed objects</span>
          <div>
            {summary.failed_objects.map((object) => (
              <code key={object}>{object}</code>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
};

const formatLabel = (value: string): string =>
  value
    .split("_")
    .map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`)
    .join(" ");

const driftItemKey = (item: DriftItem): string =>
  JSON.stringify([
    item.schema,
    item.object_type,
    item.name,
    item.drift_type,
    item.source_file ?? "live",
    item.checksum ?? item.actual
  ]);

const driftDetailId = (item: DriftItem): string => `drift-detail-${hashString(driftItemKey(item))}`;

const hashString = (value: string): string => {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) >>> 0;
  }
  return hash.toString(36);
};

const countByDriftType = (items: DriftItem[]): Map<DriftType, number> => {
  const counts = new Map<DriftType, number>();
  items.forEach((item) => counts.set(item.drift_type, (counts.get(item.drift_type) ?? 0) + 1));
  return new Map(Array.from(counts.entries()).sort((a, b) => a[0].localeCompare(b[0])));
};

const formatOptionalDate = (value?: string | null): string => (value ? new Date(value).toLocaleString() : "Not recorded");
