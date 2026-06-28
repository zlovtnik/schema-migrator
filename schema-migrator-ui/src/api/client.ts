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
const ENCRYPT_KEY = "schemaMigrator.encryptKey";
const DEV_AUTH_SECRET = import.meta.env.VITE_DEV_AUTH_SECRET?.trim() || "";
const DEV_AUTH_SUBJECT = import.meta.env.VITE_DEV_AUTH_SUBJECT?.trim() || "docker-dev";
const DEV_AUTH_ATTEMPTS = 45;
const DEV_AUTH_RETRY_MS = 1000;
let authToken = "";
let devAuthTokenRequest: Promise<string> | undefined;

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
  const configured = stored || import.meta.env.VITE_API_BASE_URL || "/api";
  return normalizeApiBaseUrl(configured);
};

export const setApiBaseUrl = (value: string): void => {
  const normalized = normalizeApiBaseUrl(value);
  window.localStorage.setItem(API_BASE_KEY, normalized);
};

export const getEncryptKey = (): string => {
  return window.sessionStorage.getItem(ENCRYPT_KEY) || "";
};

export const setEncryptKey = (value: string): void => {
  const trimmed = value.trim();
  window.localStorage.removeItem(ENCRYPT_KEY);
  if (trimmed) {
    window.sessionStorage.setItem(ENCRYPT_KEY, trimmed);
  } else {
    window.sessionStorage.removeItem(ENCRYPT_KEY);
  }
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
type AuthTokenResponse = {
  token: string;
};

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

type EncryptedEnvelope = {
  data: string;
  iv: string;
};

const base64ToBytes = (value: string): Uint8Array => {
  const binary = window.atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
};

export const validateEncryptKey = (value: string): string | undefined => {
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }

  try {
    const bytes = base64ToBytes(trimmed);
    return bytes.byteLength === 32 ? undefined : "AES-GCM key must decode to 32 bytes";
  } catch {
    return "AES-GCM key must be valid Base64";
  }
};

const toArrayBuffer = (bytes: Uint8Array): ArrayBuffer => {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
};

const decryptEnvelope = async (text: string): Promise<string> => {
  const key = getEncryptKey();
  if (!key) {
    throw new Error("Encrypted response received but no AES key is configured");
  }

  const envelope = JSON.parse(text) as EncryptedEnvelope;
  const cryptoKey = await window.crypto.subtle.importKey("raw", toArrayBuffer(base64ToBytes(key)), "AES-GCM", false, ["decrypt"]);
  const plain = await window.crypto.subtle.decrypt(
    { name: "AES-GCM", iv: toArrayBuffer(base64ToBytes(envelope.iv)) },
    cryptoKey,
    toArrayBuffer(base64ToBytes(envelope.data))
  );
  return new TextDecoder().decode(plain);
};

const readResponseText = async (response: Response): Promise<string> => {
  const text = await response.text();
  if (response.headers.get("X-Bedrock-Encrypted") === "1") {
    return decryptEnvelope(text);
  }
  return text;
};

const sleep = (ms: number): Promise<void> => new Promise((resolve) => window.setTimeout(resolve, ms));

const fetchDevAuthToken = async (): Promise<string> => {
  const response = await fetch(buildApiUrl("/auth/token"), {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      secret: DEV_AUTH_SECRET,
      subject: DEV_AUTH_SUBJECT
    })
  });

  if (!response.ok) {
    const detail = await readErrorBody(response);
    const message =
      typeof detail === "object" && detail !== null && "error" in detail
        ? String((detail as { error: unknown }).error)
        : `Dev auth failed with ${response.status}`;
    throw new ApiError(message, response.status, detail);
  }

  const body = (await response.json()) as AuthTokenResponse;
  if (!body.token) {
    throw new Error("Dev auth response did not include a token");
  }

  setAuthToken(body.token);
  return body.token;
};

export const ensureAuthToken = async (): Promise<string> => {
  if (authToken) {
    return authToken;
  }

  if (!DEV_AUTH_SECRET) {
    return "";
  }

  if (!devAuthTokenRequest) {
    devAuthTokenRequest = (async () => {
      let lastError: unknown;
      for (let attempt = 1; attempt <= DEV_AUTH_ATTEMPTS; attempt += 1) {
        try {
          return await fetchDevAuthToken();
        } catch (error) {
          lastError = error;
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
            throw error;
          }
          if (attempt < DEV_AUTH_ATTEMPTS) {
            await sleep(DEV_AUTH_RETRY_MS);
          }
        }
      }
      throw lastError instanceof Error ? lastError : new Error("Dev auth failed");
    })().finally(() => {
      devAuthTokenRequest = undefined;
    });
  }

  return devAuthTokenRequest;
};

export const apiRequest = async <T>(path: string, options: ApiRequestOptions = {}): Promise<T> => {
  const headers = new Headers(options.headers);
  await ensureAuthToken();
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
    credentials: options.credentials ?? "same-origin",
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
  if (response.headers.get("X-Bedrock-Encrypted") === "1") {
    const decrypted = await decryptEnvelope(text);
    return (decrypted === "" ? undefined : JSON.parse(decrypted)) as T;
  }
  return (text === "" ? undefined : JSON.parse(text)) as T;
};
