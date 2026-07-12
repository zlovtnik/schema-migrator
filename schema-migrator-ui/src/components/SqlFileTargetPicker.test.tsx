import { useState } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SqlFileTargetPicker } from "./SqlFileTargetPicker";
import type { SqlFileEntry } from "../api/sqlFiles";

const ExpandedFoldersStorageKey = "schemaMigrator.sqlFilePicker.expandedFolders";

const files: SqlFileEntry[] = [
  {
    path: "tables/001_devices.sql",
    folder: "tables",
    filename: "001_devices.sql",
    sha256: "aaaaaaaa11111111bbbbbbbb22222222cccccccc33333333dddddddd44444444",
    content_base64: "",
    uploaded_at: "2026-06-28T12:00:00Z"
  },
  {
    path: "views/010_devices_view.sql",
    folder: "views",
    filename: "010_devices_view.sql",
    sha256: "bbbbbbbb11111111cccccccc22222222dddddddd33333333eeeeeeee44444444",
    content_base64: "",
    uploaded_at: "2026-06-28T12:00:00Z"
  },
  {
    path: "oracle/tables/001_accounts.sql",
    folder: "oracle/tables",
    filename: "001_accounts.sql",
    sha256: "cccccccc11111111dddddddd22222222eeeeeeee33333333ffffffff44444444",
    content_base64: "",
    uploaded_at: "2026-06-28T12:00:00Z"
  }
];

const renderPicker = (initialSelectedPaths: string[] = []) => {
  const onSelectedPathsChange = vi.fn();

  const Harness = () => {
    const [selectedPaths, setSelectedPaths] = useState(initialSelectedPaths);
    return (
      <SqlFileTargetPicker
        files={files}
        selectedPaths={selectedPaths}
        onSelectedPathsChange={(paths) => {
          onSelectedPathsChange(paths);
          setSelectedPaths(paths);
        }}
      />
    );
  };

  render(<Harness />);
  return { onSelectedPathsChange };
};

describe("SqlFileTargetPicker", () => {
  afterEach(() => {
    cleanup();
  });

  it("toggles folder details without reading a cleared React event", async () => {
    const user = userEvent.setup();

    renderPicker();

    await user.click(screen.getByText("tables"));

    expect(screen.getByText("tables/001_devices.sql")).toBeInTheDocument();
  });

  it("selects and deselects individual files", async () => {
    const user = userEvent.setup();
    const { onSelectedPathsChange } = renderPicker();

    await user.click(screen.getByRole("button", { name: /expand all/i }));
    const checkbox = screen.getByRole("checkbox", { name: /tables\/001_devices\.sql/i });

    await user.click(checkbox);
    expect(onSelectedPathsChange).toHaveBeenLastCalledWith(["tables/001_devices.sql"]);
    expect(checkbox).toBeChecked();

    await user.click(checkbox);
    expect(onSelectedPathsChange).toHaveBeenLastCalledWith([]);
    expect(checkbox).not.toBeChecked();
  });

  it("filters search results and expands matching folders", async () => {
    const user = userEvent.setup();
    renderPicker();

    await user.type(screen.getByLabelText("Filter SQL files"), "view");

    expect(screen.getByText("views/010_devices_view.sql")).toBeInTheDocument();
    expect(screen.queryByText("tables/001_devices.sql")).not.toBeInTheDocument();
    expect(screen.getByText("1 of 3 files")).toBeInTheDocument();
  });

  it("expands and collapses all visible folders", async () => {
    const user = userEvent.setup();
    renderPicker();

    await user.click(screen.getByRole("button", { name: /expand all/i }));
    expect(screen.getByText("tables/001_devices.sql")).toBeInTheDocument();
    expect(screen.getByText("views/010_devices_view.sql")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /collapse all/i }));
    expect(screen.queryByText("tables/001_devices.sql")).not.toBeInTheDocument();
    expect(screen.queryByText("views/010_devices_view.sql")).not.toBeInTheDocument();
  });

  it("selects and clears visible files", async () => {
    const user = userEvent.setup();
    const { onSelectedPathsChange } = renderPicker(["tables/001_devices.sql"]);

    await user.type(screen.getByLabelText("Filter SQL files"), "view");
    await user.click(screen.getByRole("button", { name: /select visible/i }));

    expect(onSelectedPathsChange).toHaveBeenLastCalledWith(["tables/001_devices.sql", "views/010_devices_view.sql"]);
    expect(screen.getByRole("button", { name: /clear visible/i })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /clear visible/i }));
    expect(onSelectedPathsChange).toHaveBeenLastCalledWith(["tables/001_devices.sql"]);
  });

  it("persists expanded folder state in localStorage", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(ExpandedFoldersStorageKey, JSON.stringify({ tables: true }));

    renderPicker();

    expect(screen.getByText("tables/001_devices.sql")).toBeInTheDocument();
    expect(screen.queryByText("views/010_devices_view.sql")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /expand all/i }));

    expect(JSON.parse(window.localStorage.getItem(ExpandedFoldersStorageKey) ?? "{}")).toEqual({
      "oracle/tables": true,
      tables: true,
      views: true
    });
  });
});
