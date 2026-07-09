import { useCallback, useEffect, useMemo, useState } from "react";
import { BracketsCurlyIcon } from "@phosphor-icons/react/dist/csr/BracketsCurly";
import { CopyIcon } from "@phosphor-icons/react/dist/csr/Copy";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { FunctionIcon } from "@phosphor-icons/react/dist/csr/Function";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { TableIcon } from "@phosphor-icons/react/dist/csr/Table";
import { TreeStructureIcon } from "@phosphor-icons/react/dist/csr/TreeStructure";
import { PageHeader } from "../../components/PageHeader";
import { MigrationTimeline } from "../../components/MigrationTimeline";
import { SqlPreviewPane } from "../../components/SqlPreviewPane";
import { StatusBadge } from "../../components/StatusBadge";
import { TargetSelector } from "../../components/TargetSelector";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon, type IconSource } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useSchemaCatalog } from "../../hooks/useSchema";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import type { ObjectType, SchemaCatalogObject } from "../../types";

type ObjectFilter = ObjectType | "all";

export const SchemaPage = () => {
  const selectedTarget = useSelectedTargetId();
  const { data, isLoading, error } = useSchemaCatalog(selectedTarget);
  const [filter, setFilter] = useState<ObjectFilter>("all");
  const [textFilter, setTextFilter] = useState("");
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [copyStatus, setCopyStatus] = useState<string | null>(null);
  const [copyError, setCopyError] = useState<string | null>(null);

  const objects = data?.objects ?? [];
  const filteredObjects = useMemo(
    () =>
      objects.filter((object) => {
        const typeMatch = filter === "all" || object.object_type === filter;
        const query = textFilter.trim().toLowerCase();
        const textMatch =
          !query ||
          object.name.toLowerCase().includes(query) ||
          object.schema.toLowerCase().includes(query) ||
          object.source_file?.toLowerCase().includes(query);
        return typeMatch && textMatch;
      }),
    [filter, objects, textFilter]
  );

  useEffect(() => {
    if (filteredObjects.length === 0) {
      setSelectedKey(null);
      return;
    }
    const firstObject = filteredObjects[0];
    if (!firstObject) {
      setSelectedKey(null);
      return;
    }
    if (!selectedKey || !filteredObjects.some((object) => objectKey(object) === selectedKey)) {
      setSelectedKey(objectKey(firstObject));
    }
  }, [filteredObjects, selectedKey]);

  const selectedObject = filteredObjects.find((object) => objectKey(object) === selectedKey);
  const counts = useMemo(() => countByType(objects), [objects]);
  const driftedCount = useMemo(
    () => filteredObjects.filter((object) => object.status === "drift_detected").length,
    [filteredObjects]
  );

  const handleCopyChecksum = useCallback(async (object: SchemaCatalogObject) => {
    if (!object.checksum) {
      return;
    }
    try {
      await navigator.clipboard.writeText(object.checksum);
      setCopyStatus(`Checksum copied for ${object.name}`);
      setCopyError(null);
    } catch {
      setCopyStatus(null);
      setCopyError("Could not copy the checksum to the clipboard");
    }
  }, []);

  const columns = useMemo<DataTableColumn<SchemaCatalogObject>[]>(
    () => [
      {
        id: "name",
        header: "Object name",
        className: "schema-table__object",
        sortValue: (object) => object.name,
        cell: (object) => {
          const isSelected = objectKey(object) === selectedKey;
          return (
            <button
              aria-label={object.name}
              aria-current={isSelected ? "true" : undefined}
              className={isSelected ? "link-button link-button--active" : "link-button"}
              title={object.name}
              type="button"
              onClick={() => setSelectedKey(objectKey(object))}
            >
              <Icon source={objectTypeIconSource(object.object_type)} size={16} weight="bold" />
              <code>{object.name}</code>
            </button>
          );
        }
      },
      {
        id: "type",
        header: "Type",
        className: "schema-table__type",
        sortValue: (object) => object.object_type,
        cell: (object) => {
          const label = formatObjectType(object.object_type);
          return (
            <span className="object-type-chip" title={label}>
              {label}
            </span>
          );
        }
      },
      {
        id: "schema",
        header: "Schema",
        className: "schema-table__schema",
        sortValue: (object) => object.schema,
        cell: (object) => <code title={object.schema}>{object.schema}</code>
      },
      {
        id: "status",
        header: "Status",
        className: "schema-table__status",
        sortValue: (object) => object.status,
        cell: (object) => <StatusBadge status={object.status} />
      },
      {
        id: "checked",
        header: "Last updated",
        className: "schema-table__checked",
        sortValue: (object) => object.last_checked,
        cell: (object) => {
          const absoluteDate = formatDate(object.last_checked);
          return (
            <time aria-label={absoluteDate} dateTime={object.last_checked} title={absoluteDate}>
              {formatRelativeDate(object.last_checked)}
            </time>
          );
        }
      }
    ],
    [selectedKey]
  );

  return (
    <section className="page">
      <PageHeader
        eyebrow="Schema browser"
        title="Tracked objects"
        description="Inspect manifest-defined and live Postgres schema objects for the selected target."
        actions={<TargetSelector />}
      />

      {!selectedTarget ? (
        <EmptyState icon={<Icon source={DatabaseIcon} size={24} />} title="Select a target">
          Choose a target to inspect schema objects and compare live catalog state.
        </EmptyState>
      ) : null}

      {selectedTarget && isLoading ? <Skeleton rows={8} label="Loading schema catalog" /> : null}

      {selectedTarget && error ? (
        <div className="status-banner status-banner--error" role="alert">
          Schema catalog could not be loaded.
        </div>
      ) : null}

      {data?.warnings.length ? (
        <div className="status-banner" role="status">
          {data.warnings.join(" ")}
        </div>
      ) : null}

      {data && !data.supported ? (
        <EmptyState icon={<Icon source={DatabaseIcon} size={24} />} title="Schema catalog unsupported">
          {data.db_kind === "oracle"
            ? "Oracle schema browsing is limited to connection-level checks until an Oracle catalog target is available."
            : "This target does not expose schema catalog data."}
        </EmptyState>
      ) : null}

      {data?.supported ? (
        <div className="schema-workspace">
          <nav className="schema-tree" aria-label="Schema object groups">
            <button
              className={filter === "all" ? "schema-tree__item schema-tree__item--active" : "schema-tree__item"}
              type="button"
              onClick={() => setFilter("all")}
              aria-pressed={filter === "all"}
            >
              <span className="schema-tree__label">
                <Icon source={DatabaseIcon} size={16} weight="bold" />
                All objects
              </span>
              <strong>{objects.length}</strong>
            </button>
            {Array.from(counts.entries()).map(([type, count]) => (
              <button
                className={filter === type ? "schema-tree__item schema-tree__item--active" : "schema-tree__item"}
                key={type}
                type="button"
                onClick={() => setFilter(type)}
                aria-pressed={filter === type}
              >
                <span className="schema-tree__label">
                  <Icon source={objectTypeIconSource(type)} size={16} weight="bold" />
                  {formatObjectType(type)}
                </span>
                <strong>{count}</strong>
              </button>
            ))}
          </nav>

          <div className="schema-workspace__main">
            <div className="schema-table-toolbar">
              <label className="list-filter schema-table-toolbar__filter">
                Filter schema objects
                <input
                  data-list-filter
                  name="schema-object-filter"
                  autoComplete="off"
                  value={textFilter}
                  onChange={(event) => setTextFilter(event.target.value)}
                />
              </label>
              <div className="schema-table-toolbar__meta" aria-live="polite">
                <strong>{filteredObjects.length}</strong> {formatObjectCount(filter, filteredObjects.length)}
                <span>{driftedCount} drifted</span>
              </div>
            </div>
            <DataTable
              caption="Schema objects"
              columns={columns}
              rows={filteredObjects}
              rowKey={objectKey}
              getRowState={(object) => ({ selected: objectKey(object) === selectedKey })}
              empty={
                textFilter
                  ? `No schema objects match "${textFilter}".`
                  : filter === "all"
                    ? "No schema objects were returned."
                  : `No ${formatObjectType(filter)} objects were returned.`
              }
            />
            <div className="schema-workspace__detail">
              {copyError ? (
                <div className="status-banner status-banner--error" role="alert">
                  {copyError}
                </div>
              ) : null}
              {copyStatus ? (
                <div className="status-banner" role="status">
                  {copyStatus}
                </div>
              ) : null}
              <SchemaObjectDetail object={selectedObject} onCopyChecksum={handleCopyChecksum} />
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
};

const SchemaObjectDetail = ({
  object,
  onCopyChecksum
}: {
  object?: SchemaCatalogObject | undefined;
  onCopyChecksum: (object: SchemaCatalogObject) => void;
}) => {
  if (!object) {
    return (
      <aside className="object-detail" aria-label="Schema object detail">
        <EmptyState icon={<Icon source={FileSqlIcon} size={24} />} title="No object selected">
          Select a schema object to inspect source, status, and SQL definition.
        </EmptyState>
      </aside>
    );
  }

  return (
    <aside className="object-detail" aria-label={`${object.name} detail`}>
      <header className="object-detail__header">
        <div>
          <span className="eyebrow">{formatObjectType(object.object_type)}</span>
          <h2>
            <code>{object.name}</code>
          </h2>
          <p>
            Schema <code>{object.schema}</code>
          </p>
        </div>
        <StatusBadge status={object.status} />
      </header>

      <dl className="detail-grid">
        <div>
          <dt className="field-label">Source file</dt>
          <dd>{object.source_file ? <code>{object.source_file}</code> : "Live catalog only"}</dd>
        </div>
        <div>
          <dt className="field-label">Checksum</dt>
          <dd>
            {object.checksum ? (
              <button
                className="hash-chip"
                type="button"
                title={object.checksum}
                aria-label={`Copy checksum for ${object.name}`}
                onClick={() => onCopyChecksum(object)}
              >
                <Icon source={CopyIcon} size={16} />
                {object.checksum.slice(0, 10)}
              </button>
            ) : (
              <span className="hash-chip hash-chip--empty">Unavailable</span>
            )}
          </dd>
        </div>
        <div>
          <dt className="field-label">Apply status</dt>
          <dd>{object.apply_status ?? "Not recorded"}</dd>
        </div>
      </dl>

      <MigrationTimeline
        items={
          object.source_file
            ? [
                {
                  id: `${objectKey(object)}:source`,
                  label: object.source_file,
                  detail: object.apply_status ? `schema_control status ${object.apply_status}` : "Defined by SQL manifest",
                  timestamp: object.last_checked
                }
              ]
            : []
        }
      />

      <div className="sql-preview-grid">
        <SqlPreviewPane code={object.expected_ddl} title="Expected SQL" />
        <SqlPreviewPane code={object.actual_ddl} title="Actual SQL" />
      </div>
    </aside>
  );
};

const countByType = (objects: SchemaCatalogObject[]): Map<ObjectType, number> => {
  const counts = new Map<ObjectType, number>();
  objects.forEach((object) => counts.set(object.object_type, (counts.get(object.object_type) ?? 0) + 1));
  return new Map(Array.from(counts.entries()).sort((a, b) => a[0].localeCompare(b[0])));
};

const objectKey = (object: SchemaCatalogObject): string => `${object.schema}:${object.object_type}:${object.name}`;

const objectTypeIconSource = (type: ObjectType): IconSource => {
  switch (type) {
    case "table":
      return TableIcon;
    case "view":
    case "materialized_view":
      return EyeIcon;
    case "function":
    case "procedure":
      return FunctionIcon;
    case "index":
    case "schema":
      return TreeStructureIcon;
    case "type":
    case "trigger":
      return BracketsCurlyIcon;
    default:
      return DatabaseIcon;
  }
};

const formatObjectType = (type: ObjectFilter): string =>
  type
    .split("_")
    .map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`)
    .join(" ");

const formatDate = (value: string): string => new Date(value).toLocaleString();

const relativeDateFormatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

const formatRelativeDate = (value: string): string => {
  const timestamp = Date.parse(value);
  if (Number.isNaN(timestamp)) {
    return "Unknown";
  }
  const diffMs = timestamp - Date.now();
  const absDiffMs = Math.abs(diffMs);
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ["year", 365 * 24 * 60 * 60 * 1000],
    ["month", 30 * 24 * 60 * 60 * 1000],
    ["week", 7 * 24 * 60 * 60 * 1000],
    ["day", 24 * 60 * 60 * 1000],
    ["hour", 60 * 60 * 1000],
    ["minute", 60 * 1000],
    ["second", 1000]
  ];
  const [unit, unitMs] = units.find(([, threshold]) => absDiffMs >= threshold) ?? ["second", 1000];
  const amount = Math.round(diffMs / unitMs);
  return relativeDateFormatter.format(amount, unit);
};

const formatObjectCount = (type: ObjectFilter, count: number): string => {
  if (type === "all") {
    return count === 1 ? "object" : "objects";
  }
  if (count === 1) {
    return formatObjectType(type).toLowerCase();
  }
  if (type === "index") {
    return "indexes";
  }
  if (type === "schema") {
    return "schemas";
  }
  return `${formatObjectType(type).toLowerCase()}s`;
};
