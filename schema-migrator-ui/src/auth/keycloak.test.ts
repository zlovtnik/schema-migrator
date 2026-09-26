import type { KeycloakAdapter } from "keycloak-js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const adapter = vi.hoisted(() => ({
  login: vi.fn<KeycloakAdapter["login"]>(),
  logout: vi.fn<KeycloakAdapter["logout"]>(),
  register: vi.fn<KeycloakAdapter["register"]>(),
  accountManagement: vi.fn<KeycloakAdapter["accountManagement"]>(),
  redirectUri: vi.fn<KeycloakAdapter["redirectUri"]>()
}));

vi.mock("keycloak-js", async (importOriginal) => {
  const { default: Keycloak } = await importOriginal<typeof import("keycloak-js")>();
  return {
    default: class extends Keycloak {
      constructor(config: ConstructorParameters<typeof Keycloak>[0]) {
        super(config);
        const init = this.init.bind(this);
        // Exercise the real adapter initialization without navigating away from the test.
        this.init = (options) => init({ ...options, adapter, messageReceiveTimeout: 50 });
      }
    }
  };
});

describe("Keycloak sessions behind the public gateway", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    vi.stubGlobal("isSecureContext", true);
    window.__SCHEMA_MIGRATOR_CONFIG__ = {
      VITE_KEYCLOAK_URL: "https://gateway.example.internal",
      VITE_KEYCLOAK_REALM: "middleware",
      VITE_KEYCLOAK_CLIENT_ID: "schema-migrator-ui"
    };
    adapter.login.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    delete window.__SCHEMA_MIGRATOR_CONFIG__;
    window.sessionStorage.clear();
    document.querySelectorAll("iframe").forEach((iframe) => iframe.remove());
  });

  it("checks SSO through a redirect without waiting for a blocked cookie iframe", async () => {
    const { initKeycloak } = await import("./keycloak");

    await expect(initKeycloak()).resolves.toBe(false);
    await expect(initKeycloak()).resolves.toBe(false);

    expect(adapter.login).toHaveBeenCalledExactlyOnceWith({ prompt: "none" });
    expect(document.querySelector("iframe")).toBeNull();
  });

  it("starts interactive sign-in directly on the first click", async () => {
    const { loginWithKeycloak, keycloakRedirectUri } = await import("./keycloak");

    await loginWithKeycloak();

    expect(adapter.login).toHaveBeenCalledExactlyOnceWith({ redirectUri: keycloakRedirectUri() });
    expect(document.querySelector("iframe")).toBeNull();
  });

  it("refreshes the API token without relying on iframe session checks", async () => {
    const { initKeycloak, keycloak, refreshKeycloakToken } = await import("./keycloak");
    const { getAuthToken } = await import("../api/client");
    await initKeycloak({ checkSso: false });
    keycloak!.authenticated = true;
    keycloak!.token = "old-token";
    const updateToken = vi.spyOn(keycloak!, "updateToken").mockImplementation(() => {
      keycloak!.token = "refreshed-token";
      return Promise.resolve(true);
    });

    await expect(refreshKeycloakToken()).resolves.toBe("refreshed-token");

    expect(updateToken).toHaveBeenCalledExactlyOnceWith(30);
    expect(getAuthToken()).toBe("refreshed-token");
    expect(document.querySelector("iframe")).toBeNull();
  });
});
