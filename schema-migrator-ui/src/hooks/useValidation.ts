import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getValidation, rerunValidation } from "../api/validation";

export const validationKeys = {
  detail: (runId: string) => ["validation", runId] as const
};

export const useValidation = (runId?: string) =>
  useQuery({
    queryKey: runId ? validationKeys.detail(runId) : ["validation", "missing"],
    queryFn: () => getValidation(runId as string),
    enabled: Boolean(runId)
  });

export const useRerunValidation = (runId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => rerunValidation(runId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: validationKeys.detail(runId) });
    }
  });
};
