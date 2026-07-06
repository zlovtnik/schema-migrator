import Keycloak from "keycloak-js";
import { setAuthToken, setAuthTokenProvider } from "../api/client";

const trim = (value: string | undefined): string => value?.trim() || "";

const keycloakUrl = trim(import.meta.env.VITE_KEYCLOAK_URL);
const keycloakRealm = trim(import.meta.env.VITE_KEYCLOAK_REALM);
const keycloakClientId = trim(import.meta.env.VITE_KEYCLOAK_CLIENT_ID);
const configuredRedirectUri = trim(import.meta.env.VITE_KEYCLOAK_REDIRECT_URI);

export const isKeycloakConfigured = (): boolean => Boolean(keycloakUrl && keycloakRealm && keycloakClientId);

export const keycloakRedirectUri = (): string => configuredRedirectUri || `${window.location.origin}/callback`;

export const keycloakLoginUri = (): string => `${window.location.origin}/login`;

export const keycloak =
  isKeycloakConfigured() ? new Keycloak({ url: keycloakUrl, realm: keycloakRealm, clientId: keycloakClientId }) : undefined;

let initPromise: Promise<boolean> | undefined;
let refreshPromise: Promise<string> | undefined;
let passwordRefreshToken = "";

const syncToken = (): string => {
  const token = keycloak?.token || "";
  setAuthToken(token);
  return token;
};

const tokenEndpoint = (): string => `${keycloakUrl}/realms/${encodeURIComponent(keycloakRealm)}/protocol/openid-connect/token`;

type TokenResponse = {
  access_token?: string;
  refresh_token?: string;
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
      void refreshKeycloakToken();
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
        initPromise = undefined;
        throw error;
      });
  }

  return initPromise;
};

export const refreshKeycloakToken = async (): Promise<string> => {
  if (passwordRefreshToken) {
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

  passwordRefreshToken = tokenBody.refresh_token || "";
  setAuthToken(tokenBody.access_token);
  return tokenBody.access_token;
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
    setAuthToken("");
    throw tokenError(tokenBody, "Session refresh failed");
  }

  passwordRefreshToken = tokenBody.refresh_token || passwordRefreshToken;
  setAuthToken(tokenBody.access_token);
  return tokenBody.access_token;
};

export const loginWithKeycloak = async (): Promise<void> => {
  if (!keycloak) {
    throw new Error("Keycloak is not configured");
  }
  await keycloak.login({ redirectUri: keycloakRedirectUri() });
};

export const logoutFromKeycloak = async (): Promise<void> => {
  passwordRefreshToken = "";
  if (!keycloak) {
    setAuthToken("");
    return;
  }
  setAuthToken("");
  await keycloak.logout({ redirectUri: keycloakLoginUri() });
};

setAuthTokenProvider(refreshKeycloakToken);
