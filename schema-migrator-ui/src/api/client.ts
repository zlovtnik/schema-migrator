import { runtimeConfig } from "../runtimeConfig";

export class ApiError extends Error {
  status: number;
  detail: unknown;

  constructor(message: string, status: number, detail: unknown = undefined) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}

const API_BASE_KEY = "schemaMigrator.apiBaseUrl";
let authToken = "";
let authTokenProvider: (() => Promise<string>) | undefined;
export const AUTH_TOKEN_CHANGED_EVENT = "schema-migrator-auth-token-changed";

const trimTrailingSlash = (value: string) => value.replace(/\/+$/, "") || "/";
const hasUrlScheme = (value: string) => /^[a-z][a-z0-9+.-]*:\/\//i.test(value);

const normalizeApiBaseUrl = (value: string): string => {
  const trimmed = value.trim() || "/api";
  const rooted = hasUrlScheme(trimmed) || trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  const trailingTrimmed = trimTrailingSlash(rooted);
  return trailingTrimmed === "/" ? "" : trailingTrimmed;
};

export const getApiBaseUrl = (): string => {
  const stored = window.localStorage.getItem(API_BASE_KEY);
  const configured = stored || runtimeConfig("VITE_API_BASE_URL") || import.meta.env.VITE_API_BASE_URL || "/api";
  return normalizeApiBaseUrl(configured);
};

export const setApiBaseUrl = (value: string): void => {
  const normalized = normalizeApiBaseUrl(value);
  window.localStorage.setItem(API_BASE_KEY, normalized);
};

export const getAuthToken = (): string => authToken;

export const setAuthToken = (value: string): void => {
  const next = value.trim();
  if (authToken === next) {
    return;
  }
  authToken = next;
  window.dispatchEvent(new Event(AUTH_TOKEN_CHANGED_EVENT));
};

export const setAuthTokenProvider = (provider: (() => Promise<string>) | undefined): void => {
  authTokenProvider = provider;
};

export const buildApiUrl = (path: string): string => {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${getApiBaseUrl()}${normalizedPath}`;
};

type JsonBody = object;

export interface ApiRequestOptions extends Omit<RequestInit, "body"> {
  body?: BodyInit | JsonBody;
}

const readErrorBody = async (response: Response): Promise<unknown> => {
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const text = await readResponseText(response);
    return text === "" ? undefined : JSON.parse(text);
  }
  return readResponseText(response);
};

const readResponseText = async (response: Response): Promise<string> => {
  return response.text();
};

export const ensureAuthToken = async (): Promise<string> => {
  if (!authTokenProvider) {
    return authToken;
  }

  const nextToken = await authTokenProvider();
  setAuthToken(nextToken);
  return getAuthToken();
};

export const apiRequest = async <T>(path: string, options: ApiRequestOptions = {}): Promise<T> => {
  let token: string;
  try {
    token = await ensureAuthToken();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Authentication failed";
    throw new ApiError(message, 401, error);
  }
  const { body, ...requestOptions } = options;
  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;
  const isBlob = typeof Blob !== "undefined" && body instanceof Blob;
  const isUrlSearchParams = typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams;

  const requestBody =
    body === undefined
      ? undefined
      : isFormData || isBlob || isUrlSearchParams || typeof body === "string"
        ? (body as BodyInit)
        : JSON.stringify(body);

  const send = (nextToken: string): Promise<Response> => {
    const headers = new Headers(options.headers);
    if (nextToken) {
      headers.set("Authorization", `Bearer ${nextToken}`);
    }
    if (body !== undefined && !isFormData && !isBlob && !isUrlSearchParams && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }

    const requestInit: RequestInit = {
      ...requestOptions,
      credentials: options.credentials ?? "same-origin",
      headers
    };
    if (requestBody !== undefined) {
      requestInit.body = requestBody;
    }
    return fetch(buildApiUrl(path), requestInit);
  };

  let response = await send(token);

  if (response.status === 401) {
    setAuthToken("");
    try {
      token = await ensureAuthToken();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Authentication failed";
      throw new ApiError(message, 401, error);
    }
    response = await send(token);
  }

  if (!response.ok) {
    const detail = await readErrorBody(response);
    const message =
      typeof detail === "object" && detail !== null && "error" in detail
        ? String((detail as { error: unknown }).error)
        : `Request failed with ${response.status}`;
    throw new ApiError(message, response.status, detail);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    const text = await readResponseText(response);
    return (text === "" ? undefined : text) as T;
  }

  const text = await readResponseText(response);
  return (text === "" ? undefined : JSON.parse(text)) as T;
};
