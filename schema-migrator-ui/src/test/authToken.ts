const base64UrlJson = (value: unknown): string =>
  globalThis.btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/u, "");

export const tokenWithRole = (role: string): string =>
  `${base64UrlJson({ alg: "none", typ: "JWT" })}.${base64UrlJson({ sub: "test-user", role })}.signature`;
