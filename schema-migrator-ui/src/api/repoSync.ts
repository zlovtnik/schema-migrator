import { apiRequest } from "./client";

export interface RepoSyncResult {
  added: number;
  removed: number;
  changed: number;
  unchanged: number;
  commit_sha: string;
  synced_at: string;
}

export interface RepoSyncStatus {
  target_id: string;
  repo_url: string;
  repo_branch: string;
  repo_sql_path: string;
  last_synced_commit?: string | null;
  last_synced_at?: string | null;
  remote_head_commit?: string | null;
  drift: boolean;
  remote_error?: string | null;
}

export const triggerRepoSync = (targetId: string): Promise<RepoSyncResult> =>
  apiRequest<RepoSyncResult>(`/targets/${targetId}/repo-sync`, { method: "POST" });

export const getRepoSyncStatus = (targetId: string): Promise<RepoSyncStatus> =>
  apiRequest<RepoSyncStatus>(`/targets/${targetId}/repo-sync/status`);
