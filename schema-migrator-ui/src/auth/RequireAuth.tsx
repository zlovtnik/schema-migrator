import { useEffect, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { AUTH_TOKEN_CHANGED_EVENT, getAuthToken } from "../api/client";
import { initKeycloak, isKeycloakConfigured, keycloak } from "./keycloak";

type AuthStatus = "checking" | "authenticated" | "anonymous";

export const RequireAuth = () => {
  const location = useLocation();
  const [status, setStatus] = useState<AuthStatus>(() => (getAuthToken() ? "authenticated" : "checking"));

  useEffect(() => {
    let active = true;

    const refresh = () => {
      if (!active) {
        return;
      }
      const hasSession = Boolean(getAuthToken()) || keycloak?.authenticated === true;
      setStatus(hasSession ? "authenticated" : "anonymous");
    };

    if (!isKeycloakConfigured()) {
      setStatus("anonymous");
    } else {
      initKeycloak().then(refresh).catch(() => {
        if (active) {
          setStatus("anonymous");
        }
      });
    }

    window.addEventListener(AUTH_TOKEN_CHANGED_EVENT, refresh);
    return () => {
      active = false;
      window.removeEventListener(AUTH_TOKEN_CHANGED_EVENT, refresh);
    };
  }, []);

  if (status === "checking") {
    return <div className="page empty-state" role="status">Checking session...</div>;
  }

  if (status !== "authenticated") {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <Outlet />;
};
