import { apiRequest } from "./client";
import {
  parseSnapshot,
  parseSnapshotDiff,
  parseSnapshotList,
  parseRun,
  type RollbackToSnapshotPayload,
  type Run,
  type Snapshot,
  type SnapshotDiff,
  type CreateSnapshotPayload
} from "../types";

export const listSnapshots = async (targetId: string): Promise<Snapshot[]> => {
  const response = await apiRequest<unknown>(`/snapshots?target_id=${encodeURIComponent(targetId)}`);
  return parseSnapshotList(response);
};

export const createSnapshot = (payload: CreateSnapshotPayload): Promise<Snapshot> =>
  apiRequest<unknown>("/snapshots", {
    method: "POST",
    body: payload
  }).then(parseSnapshot);

export const getSnapshot = async (id: string): Promise<Snapshot> =>
  parseSnapshot(await apiRequest<unknown>(`/snapshots/${id}`));

export const diffSnapshots = async (id: string, otherId: string): Promise<SnapshotDiff> =>
  parseSnapshotDiff(await apiRequest<unknown>(`/snapshots/${id}/diff/${otherId}`));

export const rollbackToSnapshot = (payload: RollbackToSnapshotPayload): Promise<Run> =>
  apiRequest<unknown>(`/snapshots/${payload.snapshot_id}/rollback`, {
    method: "POST",
    body: payload
  }).then(parseRun);
