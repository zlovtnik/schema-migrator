import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp } from "../../test/render";
import { Stepper } from "./Stepper";

describe("Stepper", () => {
  it("renders complete, current, and upcoming steps", () => {
    renderApp(<Stepper steps={["Choose", "Check", "Run"]} currentIndex={1} label="Upgrade progress" />);
    expect(screen.getByRole("navigation", { name: "Upgrade progress" })).toBeInTheDocument();
    expect(screen.getByText("Phase 2 of 3 · Check")).toBeInTheDocument();
    expect(screen.getByText("Check").closest("li")).toHaveAttribute("aria-current", "step");
    expect(screen.getByText("Complete")).toBeInTheDocument();
    expect(screen.getByText("Upcoming")).toBeInTheDocument();
  });
});
