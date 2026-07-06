import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { setAuthToken } from "../api/client";
import { RequireAuth } from "./RequireAuth";

vi.mock("./keycloak", () => ({
  initKeycloak: vi.fn(),
  isKeycloakConfigured: () => false,
  keycloak: undefined
}));

describe("RequireAuth", () => {
  afterEach(() => {
    setAuthToken("");
  });

  it("allows bundled non-Keycloak deployments through the route guard", () => {
    render(
      <MemoryRouter initialEntries={["/overview"]}>
        <Routes>
          <Route path="/login" element={<div>Login</div>} />
          <Route element={<RequireAuth />}>
            <Route path="/overview" element={<div>Overview</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText("Overview")).toBeInTheDocument();
    expect(screen.queryByText("Login")).not.toBeInTheDocument();
  });
});
