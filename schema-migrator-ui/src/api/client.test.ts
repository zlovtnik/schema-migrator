import { afterEach, describe, expect, test, vi } from "vitest";

import {
  ApiError,
  apiRequest,
  setApiBaseUrl,
  setAuthToken,
  setAuthTokenProvider
} from "./client";

const jsonResponse = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json"
    }
  });

describe("apiRequest", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    setAuthToken("");
    setAuthTokenProvider(undefined);
    window.sessionStorage.clear();
  });

  test("sends bearer auth and JSON request bodies", async () => {
    setApiBaseUrl("/custom-api");
    setAuthToken("token-1");
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiRequest<{ ok: boolean }>("/targets", {
      method: "POST",
      body: { label: "prod" }
    });

    expect(result).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe("/custom-api/targets");
    expect(init?.method).toBe("POST");
    expect(init?.body).toBe(JSON.stringify({ label: "prod" }));
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer token-1");
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
  });

  test("refreshes the auth token once after a 401 response", async () => {
    const provider = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("initial-token")
      .mockResolvedValueOnce("fresh-token");
    setAuthTokenProvider(provider);
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ error: "expired" }, 401))
      .mockResolvedValueOnce(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await apiRequest<{ ok: boolean }>("/runs");

    expect(result).toEqual({ ok: true });
    expect(provider).toHaveBeenCalledTimes(2);
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization")).toBe("Bearer initial-token");
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("Authorization")).toBe("Bearer fresh-token");
  });

  test("throws a typed API error for JSON error responses", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ error: "not allowed" }, 403)));

    await expect(apiRequest("/targets")).rejects.toMatchObject(
      new ApiError("not allowed", 403, { error: "not allowed" })
    );
  });

});
