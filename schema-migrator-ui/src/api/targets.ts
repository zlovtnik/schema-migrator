import { apiRequest } from "./client";
import type { ConnectionTestResult, Target, TargetPayload } from "../types";

export const listTargets = async (): Promise<Target[]> => {
  const response = await apiRequest<Target[] | { targets: Target[] }>("/targets");
  if (Array.isArray(response)) return response;
  return (response as { targets: Target[] }).targets ?? [];
};

export const getTarget = (id: string): Promise<Target> => apiRequest<Target>(`/targets/${id}`);

export const createTarget = (payload: TargetPayload): Promise<Target> =>
  apiRequest<Target>("/targets", {
    method: "POST",
    body: payload
  });

export const updateTarget = (id: string, payload: TargetPayload): Promise<Target> =>
  apiRequest<Target>(`/targets/${id}`, {
    method: "PUT",
    body: payload
  });

export const deleteTarget = (id: string): Promise<void> =>
  apiRequest<void>(`/targets/${id}`, {
    method: "DELETE"
  });

export const testTargetConnection = (id: string): Promise<ConnectionTestResult> =>
  apiRequest<ConnectionTestResult>(`/targets/${id}/test`, {
    method: "POST"
  });

export const testUnsavedTargetConnection = (payload: TargetPayload): Promise<ConnectionTestResult> =>
  apiRequest<ConnectionTestResult>("/targets/test", {
    method: "POST",
    body: payload
  });
