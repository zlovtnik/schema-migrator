import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createTarget,
  deleteTarget,
  getTarget,
  listTargets,
  testTargetConnection,
  testUnsavedTargetConnection,
  updateTarget
} from "../api/targets";
import { normalizeTargetPayload, type TargetFormValues, type TargetPayload } from "../types";

export const targetKeys = {
  all: ["targets"] as const,
  detail: (id: string) => ["targets", id] as const
};

export const useTargets = () =>
  useQuery({
    queryKey: targetKeys.all,
    queryFn: listTargets
  });

export const useTarget = (id?: string) =>
  useQuery({
    queryKey: id ? targetKeys.detail(id) : ["targets", "missing"],
    queryFn: () => getTarget(id as string),
    enabled: Boolean(id)
  });

export const useCreateTarget = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (values: TargetFormValues) => createTarget(normalizeTargetPayload(values)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: targetKeys.all });
    }
  });
};

export const useUpdateTarget = (id: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (values: TargetFormValues) => updateTarget(id, normalizeTargetPayload(values)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: targetKeys.all });
      void queryClient.invalidateQueries({ queryKey: targetKeys.detail(id) });
    }
  });
};

export const useDeleteTarget = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deleteTarget,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: targetKeys.all });
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
    }
  });
};

export const useTestConnection = () =>
  useMutation({
    mutationFn: ({ id, values }: { id?: string; values?: TargetFormValues | TargetPayload }) => {
      if (values) {
        return testUnsavedTargetConnection("password" in values ? normalizeTargetPayload(values as TargetFormValues) : values);
      }
      if (id) {
        return testTargetConnection(id);
      }
      throw new Error("Connection values are required for pre-save tests");
    }
  });
