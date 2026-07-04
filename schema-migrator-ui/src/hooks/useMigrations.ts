import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { deleteMigration, getMigration, listMigrations, uploadMigration } from "../api/migrations";
import { triggerRun } from "../api/runs";
import type { TriggerRunPayload, UploadPatchPayload } from "../types";

export const migrationKeys = {
  all: ["migrations"] as const,
  list: (targetId?: string | null) => ["migrations", "list", targetId || "none"] as const,
  detail: (id: string) => ["migrations", "detail", id] as const
};

export const useMigrations = (targetId?: string | null) =>
  useQuery({
    queryKey: migrationKeys.list(targetId),
    queryFn: () => listMigrations(targetId as string),
    enabled: Boolean(targetId)
  });

export const useMigration = (id?: string) =>
  useQuery({
    queryKey: id ? migrationKeys.detail(id) : ["migrations", "detail", "missing"],
    queryFn: () => getMigration(id as string),
    enabled: Boolean(id)
  });

export const useUploadMigration = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: UploadPatchPayload) => uploadMigration(payload),
    onSuccess: (migration) => {
      void queryClient.invalidateQueries({ queryKey: migrationKeys.list(migration.target_id) });
    }
  });
};

export const useDeleteMigration = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deleteMigration,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: migrationKeys.all });
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
      void queryClient.invalidateQueries({ queryKey: migrationKeys.list(run.target_id) });
      navigate(`/runs/${run.id}`);
    }
  });
};
