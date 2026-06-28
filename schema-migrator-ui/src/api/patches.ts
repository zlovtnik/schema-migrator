import { apiRequest } from "./client";
import { parsePatch, parsePatchList, type Patch, type UploadPatchPayload } from "../types";

export const listPatches = async (targetId: string): Promise<Patch[]> => {
  const response = await apiRequest<unknown>(`/patches?target_id=${encodeURIComponent(targetId)}`);
  return parsePatchList(response);
};

export const getPatch = async (id: string): Promise<Patch> => parsePatch(await apiRequest<unknown>(`/patches/${id}`));

export const uploadPatch = ({ target_id, files }: UploadPatchPayload): Promise<Patch> => {
  const formData = new FormData();
  formData.set("target_id", target_id);
  files.forEach((file, index) => {
    formData.append("files", file);
    formData.append("order[]", String(index + 1));
  });

  return apiRequest<unknown>("/patches", {
    method: "POST",
    body: formData
  }).then(parsePatch);
};

export const deletePatch = (id: string): Promise<void> =>
  apiRequest<void>(`/patches/${id}`, {
    method: "DELETE"
  });
