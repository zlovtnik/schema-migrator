import { apiRequest } from "./client";
import { parseRun, parseRunList, type Run, type TriggerRunPayload } from "../types";

export const triggerRun = (payload: TriggerRunPayload): Promise<Run> =>
  apiRequest<unknown>("/runs", {
    method: "POST",
    body: payload
  }).then(parseRun);

export const listRuns = async (targetId?: string | null): Promise<Run[]> => {
  const query = targetId ? `?target_id=${encodeURIComponent(targetId)}` : "";
  const response = await apiRequest<unknown>(`/runs${query}`);
  return parseRunList(response);
};

export const getRun = async (id: string): Promise<Run> => parseRun(await apiRequest<unknown>(`/runs/${id}`));

export const abortRun = (id: string): Promise<Run> =>
  apiRequest<unknown>(`/runs/${id}/abort`, {
    method: "POST"
  }).then(parseRun);

export const resolveRun = (id: string): Promise<Run> =>
  apiRequest<unknown>(`/runs/${id}/resolve`, {
    method: "POST"
  }).then(parseRun);
