import { useMemo, useState } from "react";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { ActivityTable } from "../../components/ActivityTable";
import { PageHeader } from "../../components/PageHeader";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useAuditEvents } from "../../hooks/useAudit";
import { usePatches } from "../../hooks/usePatches";
import { useRuns } from "../../hooks/useRuns";
import { useSession } from "../../hooks/useSession";
import { useTargets } from "../../hooks/useTargets";

export const AuditLogPage = () => {
  const { canViewAudit } = useSession();
  const [actor, setActor] = useState("");
  const [entityType, setEntityType] = useState("");
  const [entityId, setEntityId] = useState("");
  const [targetId, setTargetId] = useState("");
  const [limit, setLimit] = useState("100");
  const filters = useMemo(
    () => ({
      actor: actor.trim() || null,
      entity_id: entityId.trim() || null,
      entity_type: entityType.trim() || null,
      limit: Number(limit) || null,
      target_id: targetId.trim() || null
    }),
    [actor, entityId, entityType, limit, targetId]
  );
  const { data: events = [], isLoading, error } = useAuditEvents(filters, canViewAudit);
  const { data: referenceEvents = [] } = useAuditEvents({ limit: 250 }, canViewAudit);
  const { data: patches = [] } = usePatches();
  const { data: runs = [] } = useRuns();
  const { data: targets = [] } = useTargets();

  if (!canViewAudit) {
    return (
      <section className="page">
        <EmptyState icon={<Icon source={ShieldCheckIcon} size={24} />} title="Admin role required">
          Audit events are visible only to admin sessions.
        </EmptyState>
      </section>
    );
  }

  return (
    <section className="page">
      <PageHeader
        eyebrow="Admin"
        title="Audit log"
        description="Actor, action, and entity history across the schema service."
      />

      <div className="audit-filter-bar">
        <label>
          Actor
          <input
            data-list-filter
            autoComplete="off"
            value={actor}
            placeholder="Any actor"
            onChange={(event) => setActor(event.target.value)}
          />
        </label>
        <label>
          Entity type
          <select value={entityType} onChange={(event) => setEntityType(event.target.value)}>
            <option value="">All entity types</option>
            <option value="target">Targets</option>
            <option value="patch">Patches</option>
            <option value="run">Runs</option>
            <option value="snapshot">Snapshots</option>
            <option value="validation">Validation</option>
          </select>
        </label>
        <label>
          Entity ID
          <input
            autoComplete="off"
            value={entityId}
            placeholder="Any entity"
            onChange={(event) => setEntityId(event.target.value)}
          />
        </label>
        <label>
          Target ID
          <input
            autoComplete="off"
            value={targetId}
            placeholder="Any target"
            onChange={(event) => setTargetId(event.target.value)}
          />
        </label>
        <label>
          Limit
          <select value={limit} onChange={(event) => setLimit(event.target.value)}>
            <option value="25">25 events</option>
            <option value="50">50 events</option>
            <option value="100">100 events</option>
            <option value="250">250 events</option>
          </select>
        </label>
      </div>

      {isLoading ? <Skeleton rows={6} label="Loading audit events" /> : null}

      {error ? (
        <div className="status-banner status-banner--error" role="alert">
          Audit events could not be loaded.
        </div>
      ) : null}

      {!isLoading ? (
        <ActivityTable
          events={events}
          patches={patches}
          referenceEvents={referenceEvents}
          runs={runs}
          targets={targets}
          empty="No audit events match these filters."
        />
      ) : null}
    </section>
  );
};
