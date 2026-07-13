import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useId, useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ConfirmDialog } from "../ConfirmDialog";
import { Modal } from "./Modal";

const ModalHarness = () => {
  const [open, setOpen] = useState(false);
  const titleId = useId();
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open modal
      </button>
      <Modal open={open} labelledBy={titleId} onClose={() => setOpen(false)}>
        {({ close }) => ({
          body: (
            <>
              <h2 id={titleId}>Shared modal</h2>
              <button type="button">First</button>
            </>
          ),
          footer: (
            <button type="button" onClick={close}>
              Last
            </button>
          )
        })}
      </Modal>
    </>
  );
};

describe("Modal", () => {
  it("traps focus, closes on Escape, restores siblings, and returns focus", async () => {
    const view = render(<ModalHarness />);
    const trigger = screen.getByRole("button", { name: "Open modal" });
    trigger.focus();
    fireEvent.click(trigger);

    const first = screen.getByRole("button", { name: "First" });
    const last = screen.getByRole("button", { name: "Last" });
    await waitFor(() => expect(first).toHaveFocus());
    expect(view.baseElement.querySelector("body > div")).toHaveAttribute("inert");

    last.focus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(first).toHaveFocus();
    first.focus();
    fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
    expect(last).toHaveFocus();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(view.baseElement.querySelector("body > div")).not.toHaveAttribute("inert");
    expect(trigger).toHaveFocus();
  });

  it("preserves required-text and busy confirmation behavior", () => {
    const onConfirm = vi.fn();
    const { rerender } = render(
      <ConfirmDialog
        open
        title="Delete"
        message="Permanent"
        requireText="DELETE"
        onCancel={() => undefined}
        onConfirm={onConfirm}
      />
    );
    const confirm = screen.getByRole("button", { name: "Confirm" });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Type DELETE/), { target: { value: "DELETE" } });
    expect(confirm).toBeEnabled();

    rerender(
      <ConfirmDialog open title="Delete" message="Permanent" busy onCancel={() => undefined} onConfirm={onConfirm} />
    );
    expect(screen.getByRole("button", { name: "Working" })).toBeDisabled();
  });
});
