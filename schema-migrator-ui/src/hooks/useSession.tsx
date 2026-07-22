import { useEffect, useMemo, useState } from "react";
import { AUTH_TOKEN_CHANGED_EVENT, getAuthToken } from "../api/client";
import type { UserRole } from "../types";

export interface SessionState {
  role: UserRole;
  subject?: string | undefined;
  tokenPresent: boolean;
  canMutate: boolean;
  canManageTargets: boolean;
  canViewAudit: boolean;
}

const roleOrder: Record<UserRole, number> = {
  viewer: 0,
  operator: 1,
  admin: 2
};

export const useSession = (): SessionState => {
  const [token, setToken] = useState(getAuthToken);

  useEffect(() => {
    const refresh = () => setToken(getAuthToken());
    window.addEventListener(AUTH_TOKEN_CHANGED_EVENT, refresh);
    window.addEventListener("storage", refresh);
    return () => {
      window.removeEventListener(AUTH_TOKEN_CHANGED_EVENT, refresh);
      window.removeEventListener("storage", refresh);
    };
  }, []);

  return useMemo(() => {
    const claims = decodeJwtClaims(token);
    const role = roleFromClaims(claims) ?? "viewer";
    return {
      role,
      subject: typeof claims?.sub === "string" ? claims.sub : undefined,
      tokenPresent: Boolean(token),
      canMutate: roleOrder[role] >= roleOrder.operator,
      canManageTargets: roleOrder[role] >= roleOrder.admin,
      canViewAudit: roleOrder[role] >= roleOrder.admin
    };
  }, [token]);
};

const roleFromClaims = (claims: Record<string, unknown> | null): UserRole | undefined => {
  if (!claims) {
    return undefined;
  }

  const direct = normalizeRole(claims.role) ?? normalizeRole(claims["https://bedrock/role"]);
  if (direct) {
    return direct;
  }

  const roles = collectRoles(claims.roles, claims.realm_access, claims.resource_access, claims.scope);
  if (roles.includes("admin")) return "admin";
  if (roles.includes("operator")) return "operator";
  if (roles.includes("viewer")) return "viewer";
  return undefined;
};

const collectRoles = (...values: unknown[]): string[] => {
  const roles: string[] = [];
  values.forEach((value) => {
    if (Array.isArray(value)) {
      roles.push(...value.flatMap((item) => (typeof item === "string" ? [item.toLowerCase()] : [])));
      return;
    }
    if (typeof value === "string") {
      roles.push(...value.toLowerCase().split(/\s+/).filter(Boolean));
      return;
    }
    if (value && typeof value === "object") {
      const objectValue = value as Record<string, unknown>;
      if (Array.isArray(objectValue.roles)) {
        roles.push(...objectValue.roles.flatMap((item) => (typeof item === "string" ? [item.toLowerCase()] : [])));
      }
      Object.values(objectValue).forEach((nested) => {
        if (nested && typeof nested === "object" && Array.isArray((nested as Record<string, unknown>).roles)) {
          roles.push(
            ...(nested as { roles: unknown[] }).roles.flatMap((item) =>
              typeof item === "string" ? [item.toLowerCase()] : []
            )
          );
        }
      });
    }
  });
  return roles;
};

const normalizeRole = (value: unknown): UserRole | undefined => {
  if (typeof value !== "string") {
    return undefined;
  }
  const normalized = value.toLowerCase();
  if (normalized === "admin" || normalized === "operator" || normalized === "viewer") {
    return normalized;
  }
  return undefined;
};

const decodeJwtClaims = (token: string): Record<string, unknown> | null => {
  const payload = token.split(".")[1];
  if (!payload) {
    return null;
  }

  try {
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const json = window.atob(padded);
    const parsed = JSON.parse(json) as unknown;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
};
