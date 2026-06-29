import { useQuery } from "@tanstack/react-query";
import { getDrift, getSchemaCatalog } from "../api/schema";

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
