import { apiRequest } from "./client";
import type { Patch, UploadPatchPayload } from "../types";

export const listPatches = async (targetId: string): Promise<Patch[]> => {
  const response = await apiRequest<Patch[] | { patches: Patch[] }>(
    `/patches?target_id=${encodeURIComponent(targetId)}`
  );
  if (Array.isArray(response)) return response;
  return (response as { patches: Patch[] }).patches ?? [];
};

export const getPatch = (id: string): Promise<Patch> => apiRequest<Patch>(`/patches/${id}`);

export const uploadPatch = ({ target_id, files }: UploadPatchPayload): Promise<Patch> => {
  const formData = new FormData();
  formData.set("target_id", target_id);
  files.forEach((file, index) => {
    formData.append("files", file);
    formData.append("order[]", String(index + 1));
  });

  return apiRequest<Patch>("/patches", {
    method: "POST",
    body: formData
  });
};

export const deletePatch = (id: string): Promise<void> =>
  apiRequest<void>(`/patches/${id}`, {
    method: "DELETE"
  });
