import { apiRequest } from "./client";
import { parsePatch, parsePatchList, type Patch, type UploadPatchPayload } from "../types";

// The UI calls these records migrations, while the backend API and payload schema
// still expose the historical `/patches` contract.
export const listMigrations = async (targetId: string): Promise<Patch[]> => {
  const response = await apiRequest<unknown>(`/patches?target_id=${encodeURIComponent(targetId)}`);
  return parsePatchList(response);
};

export const getMigration = async (id: string): Promise<Patch> => parsePatch(await apiRequest<unknown>(`/patches/${id}`));

export const uploadMigration = ({ target_id, files }: UploadPatchPayload): Promise<Patch> => {
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

export const deleteMigration = (id: string): Promise<void> =>
  apiRequest<void>(`/patches/${id}`, {
    method: "DELETE"
  });
