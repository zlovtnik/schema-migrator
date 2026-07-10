import { apiRequest } from "./client";
import {
  parseDriftResponse,
  parseRun,
  parseSchemaCatalogResponse,
  type DriftResponse,
  type DriftRunPayload,
  type Run,
  type SchemaCatalogResponse
} from "../types";

export const getSchemaCatalog = async (targetId: string): Promise<SchemaCatalogResponse> => {
  const response = await apiRequest<unknown>(`/schema?target_id=${encodeURIComponent(targetId)}`);
  return parseSchemaCatalogResponse(response);
};

export const getDrift = async (targetId: string): Promise<DriftResponse> => {
  const response = await apiRequest<unknown>(`/drift?target_id=${encodeURIComponent(targetId)}`);
  return parseDriftResponse(response);
};

export const triggerDriftRun = (payload: DriftRunPayload): Promise<Run> =>
  apiRequest<unknown>("/drift/runs", {
    method: "POST",
    body: payload
  }).then(parseRun);
