import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getValidation, rerunValidation, validateSqlDirectory, validateSqlFiles } from "../api/validation";

export const validationKeys = {
  detail: (runId: string) => ["validation", runId] as const,
  sqlFiles: (targetId?: string | null) => ["validation", "sql-files", targetId || "none"] as const,
  sqlDirectory: (sqlDir: string, dbKind: string) => ["validation", "sql-directory", sqlDir, dbKind] as const
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

export const useValidateSqlFiles = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: validateSqlFiles,
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: validationKeys.sqlFiles(result.target_id) });
    }
  });
};

export const useValidateSqlDirectory = () =>
  useMutation({
    mutationFn: validateSqlDirectory
  });
