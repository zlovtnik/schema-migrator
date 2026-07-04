import { useQuery } from "@tanstack/react-query";
import { listAuditEvents, type AuditEventFilters } from "../api/audit";

export const auditKeys = {
  all: ["audit"] as const,
  list: (filters: AuditEventFilters = {}) =>
    [
      "audit",
      "list",
      filters.actor || "",
      filters.entity_id || "",
      filters.entity_type || "",
      filters.limit || "",
      filters.target_id || ""
    ] as const
};

export const useAuditEvents = (filters: AuditEventFilters = {}, enabled = true) =>
  useQuery({
    queryKey: auditKeys.list(filters),
    queryFn: () => listAuditEvents(filters),
    enabled
  });
