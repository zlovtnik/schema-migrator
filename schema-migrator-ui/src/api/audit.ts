import { apiRequest } from "./client";
import { parseAuditEventList, type AuditEvent } from "../types";

export interface AuditEventFilters {
  actor?: string | null;
  entity_id?: string | null;
  entity_type?: string | null;
  limit?: number | null;
  target_id?: string | null;
}

export const listAuditEvents = async (filters: AuditEventFilters = {}): Promise<AuditEvent[]> => {
  const params = new URLSearchParams();
  if (filters.actor) params.set("actor", filters.actor);
  if (filters.entity_id) params.set("entity_id", filters.entity_id);
  if (filters.entity_type) params.set("entity_type", filters.entity_type);
  if (filters.limit) params.set("limit", String(filters.limit));
  if (filters.target_id) params.set("target_id", filters.target_id);

  const query = params.toString();
  const response = await apiRequest<unknown>(query ? `/audit?${query}` : "/audit");
  return parseAuditEventList(response);
};
