import { apiRequest } from "./client";
import {
  parseConnectionTestResult,
  parseTarget,
  parseTargetList,
  type ConnectionTestResult,
  type Target,
  type TargetPayload
} from "../types";

export const listTargets = async (): Promise<Target[]> => {
  const response = await apiRequest<unknown>("/targets");
  return parseTargetList(response);
};

export const getTarget = async (id: string): Promise<Target> =>
  parseTarget(await apiRequest<unknown>(`/targets/${id}`));

export const createTarget = (payload: TargetPayload): Promise<Target> =>
  apiRequest<unknown>("/targets", {
    method: "POST",
    body: payload
  }).then(parseTarget);

export const updateTarget = (id: string, payload: TargetPayload): Promise<Target> =>
  apiRequest<unknown>(`/targets/${id}`, {
    method: "PUT",
    body: payload
  }).then(parseTarget);

export const deleteTarget = (id: string): Promise<void> =>
  apiRequest<void>(`/targets/${id}`, {
    method: "DELETE"
  });

export const testTargetConnection = (id: string): Promise<ConnectionTestResult> =>
  apiRequest<unknown>(`/targets/${id}/test`, {
    method: "POST"
  }).then(parseConnectionTestResult);

export const testUnsavedTargetConnection = (payload: TargetPayload): Promise<ConnectionTestResult> =>
  apiRequest<unknown>("/targets/test", {
    method: "POST",
    body: payload
  }).then(parseConnectionTestResult);
