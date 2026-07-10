import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { createSnapshot, diffSnapshots, getSnapshot, listSnapshots, rollbackToSnapshot } from "../api/snapshots";
import type { CreateSnapshotPayload, RollbackToSnapshotPayload } from "../types";

export const snapshotKeys = {
  all: ["snapshots"] as const,
  list: (targetId?: string | null) => ["snapshots", "list", targetId || "none"] as const,
  detail: (id: string) => ["snapshots", "detail", id] as const,
  diff: (id: string, otherId: string) => ["snapshots", "diff", id, otherId] as const
};

export const useSnapshots = (targetId?: string | null) =>
  useQuery({
    queryKey: snapshotKeys.list(targetId),
    queryFn: () => listSnapshots(targetId as string),
    enabled: Boolean(targetId)
  });

export const useCreateSnapshot = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: CreateSnapshotPayload) => createSnapshot(payload),
    onSuccess: (snapshot) => {
      void queryClient.invalidateQueries({ queryKey: snapshotKeys.list(snapshot.target_id) });
      void queryClient.invalidateQueries({ queryKey: snapshotKeys.all });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
    }
  });
};

export const useSnapshot = (id?: string) =>
  useQuery({
    queryKey: id ? snapshotKeys.detail(id) : ["snapshots", "detail", "missing"],
    queryFn: () => getSnapshot(id as string),
    enabled: Boolean(id)
  });

export const useSnapshotDiff = (id?: string, otherId?: string) =>
  useQuery({
    queryKey: id && otherId ? snapshotKeys.diff(id, otherId) : ["snapshots", "diff", "missing"],
    queryFn: () => diffSnapshots(id as string, otherId as string),
    enabled: Boolean(id && otherId)
  });

export const useRollbackToSnapshot = () => {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  return useMutation({
    mutationFn: (payload: RollbackToSnapshotPayload) => rollbackToSnapshot(payload),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
      void queryClient.invalidateQueries({ queryKey: ["patches"] });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
      navigate(`/runs/${run.id}`);
    }
  });
};
