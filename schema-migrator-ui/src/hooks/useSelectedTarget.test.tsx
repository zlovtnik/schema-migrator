import { afterEach, describe, expect, it } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { SelectedTargetProvider, useSelectedTarget, useSelectedTargetId } from "./useSelectedTarget";

const StorageKey = "schemaMigrator.selectedTargetId";

const renderProbe = (route = "/") =>
  render(
    <SelectedTargetProvider>
      <MemoryRouter initialEntries={[route]}>
        <Probe />
      </MemoryRouter>
    </SelectedTargetProvider>
  );

const Probe = () => {
  const selectedTargetId = useSelectedTargetId();
  const { setSelectedTargetId } = useSelectedTarget();

  return (
    <div>
      <span data-testid="selected-target">{selectedTargetId ?? "none"}</span>
      <button type="button" onClick={() => setSelectedTargetId(" local-target ")}>
        Set local
      </button>
      <button type="button" onClick={() => setSelectedTargetId("   ")}>
        Clear local
      </button>
    </div>
  );
};

describe("useSelectedTarget", () => {
  afterEach(() => {
    cleanup();
  });

  it("prefers the URL target over stored selection and syncs storage", async () => {
    window.localStorage.setItem(StorageKey, "stored-target");

    renderProbe("/drift?target=url-target");

    expect(screen.getByTestId("selected-target")).toHaveTextContent("url-target");
    await waitFor(() => expect(window.localStorage.getItem(StorageKey)).toBe("url-target"));
  });

  it("syncs provider state from browser storage events", () => {
    renderProbe();

    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: StorageKey, newValue: "tab-target" }));
    });

    expect(screen.getByTestId("selected-target")).toHaveTextContent("tab-target");
  });

  it("normalizes empty target values to null", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(StorageKey, "   ");

    renderProbe();
    expect(screen.getByTestId("selected-target")).toHaveTextContent("none");

    await user.click(screen.getByRole("button", { name: "Set local" }));
    expect(screen.getByTestId("selected-target")).toHaveTextContent("local-target");

    await user.click(screen.getByRole("button", { name: "Clear local" }));
    expect(screen.getByTestId("selected-target")).toHaveTextContent("none");

    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: StorageKey, newValue: "   " }));
    });
    expect(screen.getByTestId("selected-target")).toHaveTextContent("none");
  });
});
