import { afterEach, describe, expect, it } from "vitest";
import { redirectPathFromState, rememberPostAuthRedirect, takePostAuthRedirect } from "./postAuthRedirect";

const POST_AUTH_REDIRECT_KEY = "schemaMigrator.postAuthRedirect";

describe("postAuthRedirect", () => {
  afterEach(() => {
    window.sessionStorage.clear();
  });

  it("preserves valid in-app redirect paths from router state", () => {
    expect(
      redirectPathFromState({
        from: {
          pathname: "/targets",
          search: "?target_id=prod",
          hash: "#credentials"
        }
      })
    ).toBe("/targets?target_id=prod#credentials");
    expect(
      takePostAuthRedirect({
        from: {
          pathname: "/targets",
          search: "?target_id=prod",
          hash: "#credentials"
        }
      })
    ).toBe("/targets?target_id=prod#credentials");
  });

  it("rejects login and callback redirects", () => {
    expect(redirectPathFromState({ from: { pathname: "/login" } })).toBeUndefined();
    expect(redirectPathFromState({ from: { pathname: "/callback" } })).toBeUndefined();
    expect(redirectPathFromState({ from: { pathname: "//example.com" } })).toBeUndefined();
    window.sessionStorage.setItem(POST_AUTH_REDIRECT_KEY, "/overview");

    expect(takePostAuthRedirect({ from: { pathname: "/login" } })).toBe("/overview");
    window.sessionStorage.setItem(POST_AUTH_REDIRECT_KEY, "/overview");
    expect(takePostAuthRedirect({ from: { pathname: "/callback" } })).toBe("/overview");
  });

  it("stores valid redirects and uses the sessionStorage fallback once", () => {
    rememberPostAuthRedirect({ from: { pathname: "/runs", search: "?status=failed" } });

    expect(takePostAuthRedirect(undefined)).toBe("/runs?status=failed");
    expect(window.sessionStorage.getItem(POST_AUTH_REDIRECT_KEY)).toBeNull();
    expect(takePostAuthRedirect(undefined)).toBe("/overview");
  });

  it("cleans up rejected sessionStorage fallback values", () => {
    window.sessionStorage.setItem(POST_AUTH_REDIRECT_KEY, "//example.com");

    expect(takePostAuthRedirect(undefined)).toBe("/overview");
    expect(window.sessionStorage.getItem(POST_AUTH_REDIRECT_KEY)).toBeNull();
  });

  it("does not store rejected redirects", () => {
    rememberPostAuthRedirect({ from: { pathname: "/callback" } });

    expect(window.sessionStorage.getItem(POST_AUTH_REDIRECT_KEY)).toBeNull();
    expect(takePostAuthRedirect(undefined)).toBe("/overview");
  });
});
