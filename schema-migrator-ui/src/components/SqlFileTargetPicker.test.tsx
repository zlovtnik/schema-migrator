import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SqlFileTargetPicker } from "./SqlFileTargetPicker";
import type { SqlFileEntry } from "../api/sqlFiles";

const files: SqlFileEntry[] = [
  {
    path: "tables/001_devices.sql",
    folder: "tables",
    filename: "001_devices.sql",
    sha256: "aaaaaaaa11111111bbbbbbbb22222222cccccccc33333333dddddddd44444444",
    content_base64: "",
    uploaded_at: "2026-06-28T12:00:00Z"
  }
];

describe("SqlFileTargetPicker", () => {
  it("toggles folder details without reading a cleared React event", async () => {
    const user = userEvent.setup();

    render(<SqlFileTargetPicker files={files} selectedPaths={[]} onSelectedPathsChange={vi.fn()} />);

    await user.click(screen.getByText("tables"));

    expect(screen.getByText("tables/001_devices.sql")).toBeInTheDocument();
  });
});
