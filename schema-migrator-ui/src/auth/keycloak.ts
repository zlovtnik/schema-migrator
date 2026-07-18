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

const secureCryptoAvailable = (): boolean =>
  window.isSecureContext && typeof globalThis.crypto?.subtle !== "undefined";

const installRandomUuidFallback = (): void => {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return;
  }
  if (typeof globalThis.crypto?.getRandomValues !== "function") {
    throw new Error("Web Crypto random values are not available");
  }

  Object.defineProperty(globalThis.crypto, "randomUUID", {
    configurable: true,
    value: () => {
      const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6]! & 0x0f) | 0x40;
      bytes[8] = (bytes[8]! & 0x3f) | 0x80;
      const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
  });
};

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
    installRandomUuidFallback();
    keycloak.onAuthSuccess = syncToken;
    keycloak.onAuthRefreshSuccess = syncToken;
    keycloak.onAuthLogout = () => setAuthToken("");
    keycloak.onAuthRefreshError = () => setAuthToken("");
    keycloak.onTokenExpired = () => {
      void refreshKeycloakToken().catch(() => setAuthToken(""));
    };

    initPromise = keycloak
      .init({
        checkLoginIframe: secureCryptoAvailable(),
        onLoad: "check-sso",
        pkceMethod: secureCryptoAvailable() ? "S256" : false,
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
