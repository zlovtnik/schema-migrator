import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getDrift, getSchemaCatalog, triggerDriftRun } from "../api/schema";
import type { DriftRunPayload } from "../types";

export const schemaKeys = {
  catalog: (targetId?: string | null) => ["schema", "catalog", targetId || "none"] as const,
  drift: (targetId?: string | null) => ["schema", "drift", targetId || "none"] as const
};

export const useSchemaCatalog = (targetId?: string | null) =>
  useQuery({
    queryKey: schemaKeys.catalog(targetId),
    queryFn: () => getSchemaCatalog(targetId as string),
    enabled: Boolean(targetId)
  });

export const useDrift = (targetId?: string | null) =>
  useQuery({
    queryKey: schemaKeys.drift(targetId),
    queryFn: () => getDrift(targetId as string),
    enabled: Boolean(targetId)
  });

export const useTriggerDriftRun = () => {
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  return useMutation({
    mutationFn: (payload: DriftRunPayload) => triggerDriftRun(payload),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
      void queryClient.invalidateQueries({ queryKey: schemaKeys.drift(run.target_id) });
      navigate(`/runs/${run.id}`);
    }
  });
};
