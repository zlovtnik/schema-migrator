import { apiRequest } from "./client";
import {
  parsePatch,
  parsePatchList,
  type CreatePatchFromSqlFilesPayload,
  type Patch,
  type UploadPatchPayload
} from "../types";

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

export const uploadPatch = (payload: UploadPatchPayload): Promise<Patch> => {
  const form = new FormData();
  form.set("target_id", payload.target_id);
  for (const file of payload.files) {
    form.append("files", file);
  }
  return apiRequest<unknown>("/patches", {
    method: "POST",
    body: form
  }).then(parsePatch);
};

export const deletePatch = (id: string): Promise<void> =>
  apiRequest<void>(`/patches/${id}`, {
    method: "DELETE"
  });
