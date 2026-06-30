import { apiRequest } from "./client";
import { parseDriftResponse, parseSchemaCatalogResponse, type DriftResponse, type SchemaCatalogResponse } from "../types";

export const getSchemaCatalog = async (targetId: string): Promise<SchemaCatalogResponse> => {
  const response = await apiRequest<unknown>(`/schema?target_id=${encodeURIComponent(targetId)}`);
  return parseSchemaCatalogResponse(response);
};

export const getDrift = async (targetId: string): Promise<DriftResponse> => {
  const response = await apiRequest<unknown>(`/drift?target_id=${encodeURIComponent(targetId)}`);
  return parseDriftResponse(response);
};
