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

export const getSqlFileStatus = (): Promise<SqlFileStatus> => apiRequest<SqlFileStatus>("/sql-files/status");

export const listSqlFiles = (): Promise<SqlFileListResponse> => apiRequest<SqlFileListResponse>("/sql-files");

export const clearSqlFiles = (): Promise<void> => apiRequest<void>("/sql-files", { method: "DELETE" });
