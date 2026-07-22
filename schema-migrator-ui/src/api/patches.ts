import { apiRequest } from "./client";
import { parsePatch, parsePatchList, type CreatePatchFromSqlFilesPayload, type Patch } from "../types";

export const listPatches = async (targetId?: string | null): Promise<Patch[]> => {
  const query = targetId ? `?target_id=${encodeURIComponent(targetId)}` : "";
  const response = await apiRequest<unknown>(`/patches${query}`);
  return parsePatchList(response);
};

export const getPatch = async (id: string): Promise<Patch> => parsePatch(await apiRequest<unknown>(`/patches/${id}`));

export const createPatchFromSqlFiles = (payload: CreatePatchFromSqlFilesPayload): Promise<Patch> =>
  apiRequest<unknown>("/patches/from-sql-files", {
    method: "POST",
    body: payload
  }).then(parsePatch);

export const deletePatch = (id: string): Promise<void> =>
  apiRequest<void>(`/patches/${id}`, {
    method: "DELETE"
  });
