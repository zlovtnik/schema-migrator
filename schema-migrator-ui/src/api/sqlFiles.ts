import { apiRequest } from "./client";

export interface SqlFileStatus {
  loaded: boolean;
  file_count: number;
  folders: string[];
}

export interface SqlFileEntry {
  path: string;
  folder: string;
  filename: string;
  sha256: string;
  content_base64: string;
  uploaded_at: string;
}

export interface SqlFileListResponse {
  files: SqlFileEntry[];
}

export interface SqlFileUploadResponse {
  uploaded: number;
  folders: string[];
}

export const getSqlFileStatus = (): Promise<SqlFileStatus> =>
  apiRequest<SqlFileStatus>("/sql-files/status");

export const listSqlFiles = (): Promise<SqlFileListResponse> =>
  apiRequest<SqlFileListResponse>("/sql-files");

export const uploadSqlZip = (zipBlob: Blob): Promise<SqlFileUploadResponse> => {
  const formData = new FormData();
  formData.append("file", zipBlob, "sql-files.zip");
  return apiRequest<SqlFileUploadResponse>("/sql-files/upload-zip", {
    method: "POST",
    body: formData
  });
};

export const clearSqlFiles = (): Promise<void> =>
  apiRequest<void>("/sql-files", { method: "DELETE" });