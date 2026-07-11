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

const targetQuery = (targetId: string): string => `target_id=${encodeURIComponent(targetId)}`;

export const getSqlFileStatus = (targetId: string): Promise<SqlFileStatus> =>
  apiRequest<SqlFileStatus>(`/sql-files/status?${targetQuery(targetId)}`);

export const listSqlFiles = (targetId: string): Promise<SqlFileListResponse> =>
  apiRequest<SqlFileListResponse>(`/sql-files?${targetQuery(targetId)}`);

export const clearSqlFiles = (targetId: string): Promise<void> =>
  apiRequest<void>(`/sql-files?${targetQuery(targetId)}`, { method: "DELETE" });
