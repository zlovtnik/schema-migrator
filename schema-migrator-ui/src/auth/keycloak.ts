import Keycloak from "keycloak-js";
import { getAuthToken, setAuthToken, setAuthTokenProvider } from "../api/client";

const trim = (value: string | undefined): string => value?.trim() || "";

const keycloakUrl = trim(import.meta.env.VITE_KEYCLOAK_URL);
const keycloakRealm = trim(import.meta.env.VITE_KEYCLOAK_REALM);
const keycloakClientId = trim(import.meta.env.VITE_KEYCLOAK_CLIENT_ID);
const configuredRedirectUri = trim(import.meta.env.VITE_KEYCLOAK_REDIRECT_URI);
const directAccessGrantsEnabled = trim(import.meta.env.VITE_KEYCLOAK_DIRECT_ACCESS_GRANTS) === "true";
const PasswordTokenRefreshSkewMs = 30_000;

export const isKeycloakConfigured = (): boolean => Boolean(keycloakUrl && keycloakRealm && keycloakClientId);

export const keycloakRedirectUri = (): string => configuredRedirectUri || `${window.location.origin}/callback`;

export const keycloakLoginUri = (): string => `${window.location.origin}/login`;

export const keycloak =
  isKeycloakConfigured() ? new Keycloak({ url: keycloakUrl, realm: keycloakRealm, clientId: keycloakClientId }) : undefined;

let initPromise: Promise<boolean> | undefined;
let refreshPromise: Promise<string> | undefined;
let passwordRefreshToken = "";
let passwordTokenExpiresAt = 0;

const syncToken = (): string => {
  const token = keycloak?.token || "";
  setAuthToken(token);
  return token;
};

const tokenEndpoint = (): string => `${keycloakUrl}/realms/${encodeURIComponent(keycloakRealm)}/protocol/openid-connect/token`;

type TokenResponse = {
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  error?: string;
  error_description?: string;
};

const readTokenResponse = async (response: Response): Promise<TokenResponse> => {
  const text = await response.text();
  return text ? (JSON.parse(text) as TokenResponse) : {};
};

const tokenError = (body: TokenResponse, fallback: string): Error => {
  const message = body.error_description || body.error || fallback;
  return new Error(message);
};

const rememberPasswordToken = (body: TokenResponse): string => {
  passwordRefreshToken = body.refresh_token || passwordRefreshToken;
  passwordTokenExpiresAt = body.expires_in ? Date.now() + body.expires_in * 1000 : 0;
  setAuthToken(body.access_token || "");
  return body.access_token || "";
};

const shouldRefreshPasswordToken = (): boolean =>
  !getAuthToken() || passwordTokenExpiresAt === 0 || passwordTokenExpiresAt - Date.now() <= PasswordTokenRefreshSkewMs;

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
  if (passwordRefreshToken) {
    if (!shouldRefreshPasswordToken()) {
      return getAuthToken();
    }
    if (!refreshPromise) {
      refreshPromise = refreshPasswordToken().finally(() => {
        refreshPromise = undefined;
      });
    }
    return refreshPromise;
  }

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

export const loginWithCredentials = async (username: string, password: string): Promise<string> => {
  if (!isKeycloakConfigured()) {
    throw new Error("Keycloak is not configured");
  }
  if (!directAccessGrantsEnabled) {
    throw new Error("Username/password sign-in requires VITE_KEYCLOAK_DIRECT_ACCESS_GRANTS=true and Keycloak direct access grants enabled");
  }

  const body = new URLSearchParams({
    grant_type: "password",
    client_id: keycloakClientId,
    username,
    password,
    scope: "openid profile email roles"
  });
  const response = await fetch(tokenEndpoint(), {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body
  });
  const tokenBody = await readTokenResponse(response);

  if (!response.ok || !tokenBody.access_token) {
    throw tokenError(tokenBody, "Sign-in failed");
  }

  return rememberPasswordToken(tokenBody);
};

const refreshPasswordToken = async (): Promise<string> => {
  if (!passwordRefreshToken) {
    return "";
  }

  const body = new URLSearchParams({
    grant_type: "refresh_token",
    client_id: keycloakClientId,
    refresh_token: passwordRefreshToken
  });
  const response = await fetch(tokenEndpoint(), {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body
  });
  const tokenBody = await readTokenResponse(response);

  if (!response.ok || !tokenBody.access_token) {
    passwordRefreshToken = "";
    passwordTokenExpiresAt = 0;
    setAuthToken("");
    throw tokenError(tokenBody, "Session refresh failed");
  }

  return rememberPasswordToken(tokenBody);
};

export const loginWithKeycloak = async (): Promise<void> => {
  if (!keycloak) {
    throw new Error("Keycloak is not configured");
  }
  passwordRefreshToken = "";
  passwordTokenExpiresAt = 0;
  await keycloak.login({ redirectUri: keycloakRedirectUri() });
};

export const logoutFromKeycloak = async (): Promise<void> => {
  passwordRefreshToken = "";
  passwordTokenExpiresAt = 0;
  if (!keycloak) {
    setAuthToken("");
    return;
  }
  setAuthToken("");
  await keycloak.logout({ redirectUri: keycloakLoginUri() });
};

setAuthTokenProvider(refreshKeycloakToken);
