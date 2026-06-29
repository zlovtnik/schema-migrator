import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
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
import type { DriftItem } from "../../types";

export const DriftPage = () => {
  const [searchParams] = useSearchParams();
  const selectedTarget = searchParams.get("target");
  const { data, isLoading, error } = useDrift(selectedTarget);
  const [filter, setFilter] = useState("");

  const items = useMemo(() => {
    const query = filter.trim().toLowerCase();
    if (!query) {
      return data?.items ?? [];
    }
    return (data?.items ?? []).filter(
      (item) =>
        item.name.toLowerCase().includes(query) ||
        item.schema.toLowerCase().includes(query) ||
        item.object_type.toLowerCase().includes(query) ||
        item.drift_type.toLowerCase().includes(query)
    );
  }, [data?.items, filter]);

  const columns = useMemo<DataTableColumn<DriftItem>[]>(
    () => [
      {
        id: "object",
        header: "Object",
        sortValue: (item) => item.name,
        cell: (item) => <code>{item.name}</code>
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
    []
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
        data.items.length === 0 ? (
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
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
            </label>
            <DataTable
              caption="Drift results"
              columns={columns}
              rows={items}
              rowKey={driftItemKey}
              empty={filter ? `No drift results match "${filter}".` : "No drift detected."}
            />
            <section className="section-block">
              <h2>Drift details</h2>
              <div className="drift-detail-grid">
                {items.map((item) => (
                  <article className="drift-detail" key={driftItemKey(item)}>
                    <header>
                      <StatusBadge status={item.drift_type} />
                      <strong>
                        <code>{item.schema}.{item.name}</code>
                      </strong>
                    </header>
                    <div className="sql-preview-grid">
                      <SqlPreviewPane code={item.expected} title="Expected" />
                      <SqlPreviewPane code={item.actual} title="Actual" />
                    </div>
                  </article>
                ))}
              </div>
            </section>
          </div>
        )
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
