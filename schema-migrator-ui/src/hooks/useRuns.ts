import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { abortRun, getRun, listRuns } from "../api/runs";

export const runKeys = {
  all: ["runs"] as const,
  list: (targetId?: string | null) => ["runs", "list", targetId || "all"] as const,
  detail: (id: string) => ["runs", "detail", id] as const
};

export const useRuns = (targetId?: string | null) =>
  useQuery({
    queryKey: runKeys.list(targetId),
    queryFn: () => listRuns(targetId)
  });

export const useRun = (id?: string) =>
  useQuery({
    queryKey: id ? runKeys.detail(id) : ["runs", "detail", "missing"],
    queryFn: () => getRun(id as string),
    enabled: Boolean(id)
  });

export const useAbortRun = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: abortRun,
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: runKeys.all });
      void queryClient.invalidateQueries({ queryKey: runKeys.detail(run.id) });
    }
  });
};
