import { Link } from "react-router-dom";
import { DataTable, type DataTableColumn } from "./ui/DataTable";
import type { AuditEvent } from "../types";

interface ActivityTableProps {
  events: AuditEvent[];
  empty?: string | undefined;
}

const columns: DataTableColumn<AuditEvent>[] = [
  {
    id: "at",
    header: "At",
    sortValue: (event) => event.at,
    cell: (event) => <time dateTime={event.at}>{new Date(event.at).toLocaleString()}</time>
  },
  {
    id: "actor",
    header: "Actor",
    sortValue: (event) => event.actor,
    cell: (event) => event.actor
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
    sortValue: (event) => event.action,
    cell: (event) => <code>{event.action}</code>
  },
  {
    id: "entity",
    header: "Entity",
    sortValue: (event) => `${event.entity_type}:${event.entity_id}`,
    cell: (event) => <EntityLink event={event} />
  }
];

export const ActivityTable = ({ events, empty = "No activity found." }: ActivityTableProps) => (
  <DataTable caption="Activity" columns={columns} rows={events} rowKey={(event) => event.id} empty={empty} />
);

const EntityLink = ({ event }: { event: AuditEvent }) => {
  const to = entityUrl(event);
  const label = `${event.entity_type}:${event.entity_id}`;
  return to ? (
    <Link to={to}>
      <code>{label}</code>
    </Link>
  ) : (
    <code>{label}</code>
  );
};

const entityUrl = (event: AuditEvent): string | undefined => {
  if (event.entity_type === "target") return `/targets/${event.entity_id}/overview`;
  if (event.entity_type === "run") return `/runs/${event.entity_id}`;
  if (event.entity_type === "snapshot") return `/snapshots/${event.entity_id}`;
  return undefined;
};
