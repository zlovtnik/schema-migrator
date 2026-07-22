import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { createPatchFromSqlFiles, deletePatch, getPatch, listPatches } from "../api/patches";
import { triggerRun } from "../api/runs";
import { runKeys } from "./useRuns";
import type { CreatePatchFromSqlFilesPayload, TriggerRunPayload } from "../types";

export const patchKeys = {
  all: ["patches"] as const,
  list: (targetId?: string | null) => ["patches", "list", targetId || "all"] as const,
  detail: (id: string) => ["patches", "detail", id] as const
};

export const usePatches = (targetId?: string | null) =>
  useQuery({
    queryKey: patchKeys.list(targetId),
    queryFn: () => listPatches(targetId)
  });

export const usePatch = (id?: string) =>
  useQuery({
    queryKey: id ? patchKeys.detail(id) : ["patches", "detail", "missing"],
    queryFn: () => getPatch(id as string),
    enabled: Boolean(id)
  });

export const useCreatePatchFromSqlFiles = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: CreatePatchFromSqlFilesPayload) => createPatchFromSqlFiles(payload),
    onSuccess: (patch) => {
      void queryClient.invalidateQueries({ queryKey: patchKeys.all });
      void queryClient.invalidateQueries({ queryKey: patchKeys.detail(patch.id) });
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

export const useRunPatch = () => {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: (payload: TriggerRunPayload) => triggerRun(payload),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: runKeys.all });
      void queryClient.invalidateQueries({ queryKey: patchKeys.all });
      navigate(`/runs/${run.id}`);
    }
  });
};
