import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { getApiBaseUrl } from "../../api/client";
import { SettingsPage } from "./SettingsPage";

vi.mock("../../hooks/useTargets", () => ({
  useTargets: () => ({ data: [], isLoading: false, error: null })
}));

describe("SettingsPage", () => {
  it("saves client settings without requesting a backend encryption key", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.queryByLabelText(/AES-GCM/)).not.toBeInTheDocument();
    expect(screen.queryByText(/current AES-GCM key/)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("API base URL"), { target: { value: "/custom-api" } });
    fireEvent.click(screen.getByRole("button", { name: "Save settings" }));

    expect(getApiBaseUrl()).toBe("/custom-api");
    expect(screen.getByText("Saved")).toBeInTheDocument();
    expect(window.sessionStorage.getItem("schemaMigrator.encryptKey")).toBeNull();
  });
});
