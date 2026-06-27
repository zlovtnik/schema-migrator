import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { deletePatch, getPatch, listPatches, uploadPatch } from "../api/patches";
import { triggerRun } from "../api/runs";
import type { TriggerRunPayload, UploadPatchPayload } from "../types";

export const patchKeys = {
  all: ["patches"] as const,
  list: (targetId?: string | null) => ["patches", targetId || "none"] as const,
  detail: (id: string) => ["patches", id] as const
};

export const usePatches = (targetId?: string | null) =>
  useQuery({
    queryKey: patchKeys.list(targetId),
    queryFn: () => listPatches(targetId as string),
    enabled: Boolean(targetId)
  });

export const usePatch = (id?: string) =>
  useQuery({
    queryKey: id ? patchKeys.detail(id) : ["patches", "missing"],
    queryFn: () => getPatch(id as string),
    enabled: Boolean(id)
  });

export const useUploadPatch = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: UploadPatchPayload) => uploadPatch(payload),
    onSuccess: (patch) => {
      void queryClient.invalidateQueries({ queryKey: patchKeys.list(patch.target_id) });
    }
  });
};

export const useDeletePatch = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deletePatch,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: patchKeys.all });
    }
  });
};

export const useTriggerRun = () => {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  return useMutation({
    mutationFn: (payload: TriggerRunPayload) => triggerRun(payload),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
      void queryClient.invalidateQueries({ queryKey: patchKeys.list(run.target_id) });
      navigate(`/runs/${run.id}`);
    }
  });
};
