import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp } from "../../test/render";
import { DataTable, type DataTableColumn } from "./DataTable";

interface Row {
  id: string;
  name: string;
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
    renderApp(<DataTable caption="Objects" columns={columns} rows={[{ id: "2", name: "beta" }, { id: "1", name: "alpha" }]} rowKey={(row) => row.id} empty="Empty" />);

    expect(screen.getByRole("table", { name: "Objects" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Name" }));
    const cells = screen.getAllByRole("cell");
    expect(cells[0]).toHaveTextContent("alpha");
  });

  it("uses an explicit empty state when there are no rows", () => {
    renderApp(<DataTable caption="Objects" columns={columns} rows={[]} rowKey={(row) => row.id} empty="No objects" />);

    expect(screen.getByText("No objects")).toBeInTheDocument();
  });
});
