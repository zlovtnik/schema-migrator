import type { DbKind } from "../types";

export const dbKindForJdbcUrl = (jdbcUrl: string): DbKind | null => {
  const value = jdbcUrl.trim();
  if (value.startsWith("jdbc:oracle:thin:")) return "oracle";
  if (value.startsWith("jdbc:postgresql:") || value.startsWith("postgres://") || value.startsWith("postgresql://")) {
    return "postgres";
  }
  return null;
};

export const isOracleTarget = (jdbcUrl?: string | null): boolean =>
  jdbcUrl ? dbKindForJdbcUrl(jdbcUrl) === "oracle" : false;
