import { apiRequest } from "./client";
import type { Run, TriggerRunPayload } from "../types";

export const triggerRun = (payload: TriggerRunPayload): Promise<Run> =>
  apiRequest<Run>("/runs", {
    method: "POST",
    body: payload
  });

export const listRuns = async (targetId?: string | null): Promise<Run[]> => {
  const query = targetId ? `?target_id=${encodeURIComponent(targetId)}` : "";
  const response = await apiRequest<Run[] | { runs: Run[] }>(`/runs${query}`);
  if (Array.isArray(response)) return response;
  return (response as { runs: Run[] }).runs ?? [];
};

export const getRun = (id: string): Promise<Run> => apiRequest<Run>(`/runs/${id}`);

export const abortRun = (id: string): Promise<Run> =>
  apiRequest<Run>(`/runs/${id}/abort`, {
    method: "POST"
  });
