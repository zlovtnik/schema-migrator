import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBadge } from "./StatusBadge";

describe("StatusBadge", () => {
  it("renders status text with a non-announced decorative icon", () => {
    const { container } = render(<StatusBadge status="drift_detected" />);

    expect(screen.getByText("Drift detected")).toBeInTheDocument();
    expect(container.querySelector(".status-badge svg")).toHaveAttribute("aria-hidden", "true");
  });
});
