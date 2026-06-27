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

const trimTrailingSlash = (value: string) => value.replace(/\/+$/, "") || "/";
const hasUrlScheme = (value: string) => /^[a-z][a-z0-9+.-]*:\/\//i.test(value);

const normalizeApiBaseUrl = (value: string): string => {
  const trimmed = value.trim() || "/api";
  const rooted = hasUrlScheme(trimmed) || trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  return trimTrailingSlash(rooted);
};

export const getApiBaseUrl = (): string => {
  const stored = window.localStorage.getItem(API_BASE_KEY);
  const configured = stored || import.meta.env.VITE_API_BASE_URL || "/api";
  return normalizeApiBaseUrl(configured);
};

export const setApiBaseUrl = (value: string): void => {
  const normalized = normalizeApiBaseUrl(value);
  window.localStorage.setItem(API_BASE_KEY, normalized);
};

export const getAuthToken = (): string => authToken;

export const setAuthToken = (value: string): void => {
  authToken = value.trim();
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
    return response.json();
  }
  return response.text();
};

export const apiRequest = async <T>(path: string, options: ApiRequestOptions = {}): Promise<T> => {
  const headers = new Headers(options.headers);
  const token = getAuthToken();
  const { body, ...requestOptions } = options;
  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;
  const isBlob = typeof Blob !== "undefined" && body instanceof Blob;
  const isUrlSearchParams = typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams;

  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  if (body !== undefined && !isFormData && !isBlob && !isUrlSearchParams && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const requestInit: RequestInit = {
    ...requestOptions,
    credentials: options.credentials ?? "include",
    headers
  };
  const requestBody =
    body === undefined
      ? undefined
      : isFormData || isBlob || isUrlSearchParams || typeof body === "string"
        ? (body as BodyInit)
        : JSON.stringify(body);

  if (requestBody !== undefined) {
    requestInit.body = requestBody;
  }

  const response = await fetch(buildApiUrl(path), requestInit);

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
    const text = await response.text();
    return (text === "" ? undefined : text) as T;
  }

  const text = await response.text();
  return (text === "" ? undefined : JSON.parse(text)) as T;
};
