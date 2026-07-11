import { fireEvent, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp } from "../../test/render";
import { DataTable, type DataTableColumn } from "./DataTable";

interface Row {
  id: string;
  name: string;
  folder?: string;
}

const columns: DataTableColumn<Row>[] = [
  {
    id: "name",
    header: "Name",
    cell: (row) => row.name,
    sortValue: (row) => row.name
  }
];

describe("DataTable", () => {
  it("renders a semantic table and sorts rows from a header button", () => {
    renderApp(
      <DataTable
        caption="Objects"
        columns={columns}
        rows={[
          { id: "2", name: "beta" },
          { id: "1", name: "alpha" }
        ]}
        rowKey={(row) => row.id}
        empty="Empty"
      />
    );

    expect(screen.getByRole("table", { name: "Objects" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Name" }));
    const cells = screen.getAllByRole("cell");
    expect(cells[0]).toHaveTextContent("alpha");
  });

  it("uses an explicit empty state when there are no rows", () => {
    renderApp(<DataTable caption="Objects" columns={columns} rows={[]} rowKey={(row) => row.id} empty="No objects" />);

    expect(screen.getByText("No objects")).toBeInTheDocument();
  });

  it("renders collapsible row groups", () => {
    const view = renderApp(
      <DataTable
        caption="Objects"
        columns={columns}
        rows={[
          { id: "1", name: "alpha", folder: "cron/" },
          { id: "2", name: "beta", folder: "tables/" },
          { id: "3", name: "gamma", folder: "cron/" }
        ]}
        rowKey={(row) => row.id}
        empty="No objects"
        groupBy={(row) => row.folder ?? "(root)"}
        groupSummary={(count) => `${count} ${count === 1 ? "file" : "files"}`}
      />
    );
    const table = within(view.container);

    const cronGroup = table.getByRole("button", { name: /cron\/ 2 files/i });
    expect(cronGroup).toHaveAttribute("aria-expanded", "true");
    expect(table.getByText("alpha")).toBeInTheDocument();

    fireEvent.click(cronGroup);

    expect(cronGroup).toHaveAttribute("aria-expanded", "false");
    expect(table.queryByText("alpha")).not.toBeInTheDocument();
    expect(table.queryByText("gamma")).not.toBeInTheDocument();
    expect(table.getByText("beta")).toBeInTheDocument();
  });

  it("keeps toolbar controls visible with an empty table result", () => {
    const view = renderApp(
      <DataTable
        caption="Objects"
        columns={columns}
        rows={[]}
        rowKey={(row) => row.id}
        empty="No matching objects"
        toolbar={<input aria-label="Filter objects" type="search" />}
      />
    );
    const table = within(view.container);

    expect(table.getByRole("searchbox", { name: "Filter objects" })).toBeInTheDocument();
    expect(table.getByText("No matching objects")).toBeInTheDocument();
  });
});
