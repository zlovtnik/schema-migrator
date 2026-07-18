import Keycloak from "keycloak-js";
import { setAuthToken, setAuthTokenProvider } from "../api/client";
import { runtimeConfig } from "../runtimeConfig";

const trim = (value: string | undefined): string => value?.trim() || "";

const env = (key: string): string | undefined => {
  const viteEnv = import.meta.env as Record<string, string | undefined>;
  return runtimeConfig(key) || viteEnv[key];
};

const keycloakUrl = trim(env("VITE_KEYCLOAK_URL"));
const keycloakRealm = trim(env("VITE_KEYCLOAK_REALM"));
const keycloakClientId = trim(env("VITE_KEYCLOAK_CLIENT_ID"));
const configuredRedirectUri = trim(env("VITE_KEYCLOAK_REDIRECT_URI"));

export const isKeycloakConfigured = (): boolean => Boolean(keycloakUrl && keycloakRealm && keycloakClientId);

export const keycloakRedirectUri = (): string => configuredRedirectUri || `${window.location.origin}/callback`;

export const keycloakLoginUri = (): string => `${window.location.origin}/login`;

export const keycloak = isKeycloakConfigured()
  ? new Keycloak({ url: keycloakUrl, realm: keycloakRealm, clientId: keycloakClientId })
  : undefined;

let initPromise: Promise<boolean> | undefined;
let refreshPromise: Promise<string> | undefined;

const syncToken = (): string => {
  const token = keycloak?.token || "";
  setAuthToken(token);
  return token;
};

export const initKeycloak = async (): Promise<boolean> => {
  if (!keycloak) {
    setAuthToken("");
    return false;
  }

  if (!initPromise) {
    keycloak.onAuthSuccess = syncToken;
    keycloak.onAuthRefreshSuccess = syncToken;
    keycloak.onAuthLogout = () => setAuthToken("");
    keycloak.onAuthRefreshError = () => setAuthToken("");
    keycloak.onTokenExpired = () => {
      void refreshKeycloakToken().catch(() => setAuthToken(""));
    };

    initPromise = keycloak
      .init({
        onLoad: "check-sso",
        pkceMethod: "S256",
        redirectUri: keycloakRedirectUri()
      })
      .then((authenticated) => {
        syncToken();
        return authenticated;
      })
      .catch((error: unknown) => {
        setAuthToken("");
        throw error;
      });
  }

  return initPromise;
};

export const refreshKeycloakToken = async (): Promise<string> => {
  if (!keycloak) {
    return "";
  }

  await initKeycloak();
  if (!keycloak.authenticated) {
    setAuthToken("");
    return "";
  }

  if (!refreshPromise) {
    refreshPromise = keycloak
      .updateToken(30)
      .then(() => syncToken())
      .catch((error: unknown) => {
        keycloak.clearToken();
        setAuthToken("");
        throw error;
      })
      .finally(() => {
        refreshPromise = undefined;
      });
  }

  return refreshPromise;
};

export const loginWithKeycloak = async (): Promise<void> => {
  if (!keycloak) {
    throw new Error("Keycloak is not configured");
  }
  await initKeycloak();
  await keycloak.login({ redirectUri: keycloakRedirectUri() });
};

export const logoutFromKeycloak = async (): Promise<void> => {
  if (!keycloak) {
    setAuthToken("");
    return;
  }
  setAuthToken("");
  await keycloak.logout({ redirectUri: keycloakLoginUri() });
};

setAuthTokenProvider(refreshKeycloakToken);
