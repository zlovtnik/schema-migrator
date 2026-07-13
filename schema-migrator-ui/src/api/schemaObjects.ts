import { apiRequest } from "./client";
import { parseSchemaObjectListResponse, type SchemaObjectListResponse } from "../types";

export const getSchemaObjects = async (targetId: string): Promise<SchemaObjectListResponse> => {
  const response = await apiRequest<unknown>(`/schema/objects?target_id=${encodeURIComponent(targetId)}`);
  return parseSchemaObjectListResponse(response);
};
