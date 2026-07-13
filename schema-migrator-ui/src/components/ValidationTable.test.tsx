import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ValidationTable } from "./ValidationTable";

describe("ValidationTable", () => {
  it("filters, expands errors, and exports the visible rows safely", () => {
    const createObjectURL = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:test");
    const revokeObjectURL = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(
      <ValidationTable
        result={{
          target_id: "target-1",
          invalid: [
            { object_type: "table", schema: "public", name: "devices", severity: "error", error: "=unsafe" },
            { object_type: "view", schema: "public", name: "summary", severity: "warning", error: "long warning" }
          ]
        }}
      />
    );

    fireEvent.change(screen.getByLabelText("Object type"), { target: { value: "table" } });
    expect(screen.getByText("devices")).toBeInTheDocument();
    expect(screen.queryByText("summary")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "=unsafe" }));
    expect(screen.getByText("=unsafe", { selector: "pre" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));
    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:test");
  });
});
