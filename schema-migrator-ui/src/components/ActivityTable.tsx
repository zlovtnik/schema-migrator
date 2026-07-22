import { Link } from "react-router-dom";
import { useMemo } from "react";
import { DataTable, type DataTableColumn } from "./ui/DataTable";
import type { AuditEvent, Patch, Run, Target } from "../types";
import { formatActivityAction, formatActivityActor, formatActivityEntity } from "../utils/activityPresentation";

interface ActivityTableProps {
  events: AuditEvent[];
  empty?: string | undefined;
  compact?: boolean;
  patches?: Patch[];
  referenceEvents?: AuditEvent[];
  runs?: Run[];
  targets?: Target[];
}

export const ActivityTable = ({
  compact = false,
  events,
  empty = "No activity found.",
  patches = [],
  referenceEvents = events,
  runs = [],
  targets = []
}: ActivityTableProps) => {
  const columns = useMemo<DataTableColumn<AuditEvent>[]>(
    () => [
      {
        id: "at",
        header: "At",
        sortValue: (event) => event.at,
        cell: (event) => <time dateTime={event.at}>{new Date(event.at).toLocaleString()}</time>
      },
      {
        id: "actor",
        header: "Actor",
        sortValue: (event) => formatActivityActor(event),
        cell: (event) => {
          const label = formatActivityActor(event);
          return <span title={label === event.actor ? undefined : event.actor}>{label}</span>;
        }
      },
      {
        id: "role",
        header: "Role",
        sortValue: (event) => event.role ?? "",
        cell: (event) => event.role ?? "-"
      },
      {
        id: "action",
        header: "Action",
        sortValue: (event) => formatActivityAction(event.action),
        cell: (event) => <span title={event.action}>{formatActivityAction(event.action)}</span>
      },
      {
        id: "entity",
        header: "Entity",
        sortValue: (event) => formatActivityEntity(event, { auditEvents: referenceEvents, patches, runs, targets }),
        cell: (event) => (
          <EntityLink
            event={event}
            label={formatActivityEntity(event, { auditEvents: referenceEvents, patches, runs, targets })}
          />
        )
      }
    ],
    [patches, referenceEvents, runs, targets]
  );

  return (
    <div className={compact ? "activity-table activity-table--compact" : "activity-table"}>
      <DataTable caption="Activity" columns={columns} rows={events} rowKey={(event) => event.id} empty={empty} />
    </div>
  );
};

const EntityLink = ({ event, label }: { event: AuditEvent; label: string }) => {
  const to = entityUrl(event);
  return to ? (
    <Link to={to} title={event.entity_id}>
      {label}
    </Link>
  ) : (
    <span title={event.entity_id}>{label}</span>
  );
};

const entityUrl = (event: AuditEvent): string | undefined => {
  if (event.entity_type === "target") return `/targets/${event.entity_id}/overview`;
  if (event.entity_type === "run") return `/runs/${event.entity_id}`;
  if (event.entity_type === "snapshot") return `/snapshots/${event.entity_id}`;
  return undefined;
};
