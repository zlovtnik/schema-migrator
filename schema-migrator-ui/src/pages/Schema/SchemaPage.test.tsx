import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { SchemaPage } from "./SchemaPage";
import { renderApp } from "../../test/render";

const jsonResponse = (body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });

describe("SchemaPage", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = typeof input === "string" || input instanceof URL ? String(input) : input.url;
        if (url.includes("/targets")) {
          return Promise.resolve(
            jsonResponse({
              targets: [
                {
                  id: "target-1",
                  label: "Local",
                  app_name: "app",
                  env: "dev",
                  jdbc_url: "jdbc:postgresql://localhost/app",
                  created_at: "2026-06-28T12:00:00Z"
                }
              ]
            })
          );
        }
        if (url.includes("/schema")) {
          return Promise.resolve(
            jsonResponse({
              target_id: "target-1",
              db_kind: "postgres",
              supported: true,
              checked_at: "2026-06-28T12:00:00Z",
              warnings: [],
              objects: [
                {
                  schema: "public",
                  name: "devices",
                  object_type: "table",
                  status: "defined",
                  source_file: "tables/001_devices.sql",
                  checksum: "abc",
                  expected_ddl: "create table if not exists public.devices (id bigint primary key);",
                  last_checked: "2026-06-28T12:00:00Z"
                }
              ]
            })
          );
        }
        throw new Error(`Unexpected fetch request: ${url}`);
      })
    );
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("loads schema objects for the selected target", async () => {
    renderApp(<SchemaPage />, { route: "/schema?target=target-1" });

    expect((await screen.findAllByText("devices")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Defined").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Filter schema objects")).toBeInTheDocument();
  });

  it("loads schema objects from the saved target when the route has no target query", async () => {
    window.localStorage.setItem("schemaMigrator.selectedTargetId", "target-1");

    renderApp(<SchemaPage />, { route: "/schema" });

    expect((await screen.findAllByText("devices")).length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Filter schema objects")).toBeInTheDocument();
  });

  it("highlights the selected object row and copies the checksum chip", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText }
    });

    renderApp(<SchemaPage />, { route: "/schema?target=target-1" });

    const objectButton = await screen.findByRole("button", { name: "devices" });
    expect(objectButton.closest("tr")).toHaveAttribute("data-selected", "true");

    await user.click(screen.getByRole("button", { name: /copy checksum for devices/i }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("abc"));
    expect(await screen.findByText("Checksum copied for devices")).toBeInTheDocument();
  });

  it("has no axe violations for the loaded catalog surface", async () => {
    const { container } = renderApp(<SchemaPage />, { route: "/schema?target=target-1" });

    await screen.findAllByText("devices");
    const results = await axe.run(container, {
      rules: {
        "color-contrast": { enabled: false }
      }
    });
    await waitFor(() => expect(results.violations).toEqual([]));
  });
});
