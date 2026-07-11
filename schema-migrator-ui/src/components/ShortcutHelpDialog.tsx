import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";

interface ShortcutHelpDialogProps {
  open: boolean;
  onClose: () => void;
}

const shortcuts = [
  { keys: "Cmd/Ctrl + K", action: "Open command help" },
  { keys: "Cmd/Ctrl + \\", action: "Toggle sidebar" },
  { keys: "Cmd/Ctrl + R", action: "Refresh current data" },
  { keys: "/", action: "Focus the active list filter" },
  { keys: "Esc", action: "Close menus and dialogs" }
];

const focusableSelector = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])"
].join(",");

export const ShortcutHelpDialog = ({ open, onClose }: ShortcutHelpDialogProps) => {
  const backdropRef = useRef<HTMLDivElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const siblings = Array.from(document.body.children).filter((element) => element !== backdropRef.current);
    const siblingState = siblings.map((element) => ({
      element,
      inert: element.hasAttribute("inert"),
      ariaHidden: element.getAttribute("aria-hidden")
    }));
    siblings.forEach((element) => {
      element.setAttribute("inert", "");
      element.setAttribute("aria-hidden", "true");
    });

    const focusableElements = () =>
      Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(focusableSelector) ?? []).filter(
        (element) => element.offsetParent !== null || element === document.activeElement
      );

    const frame = window.requestAnimationFrame(() => {
      const firstFocusable = focusableElements()[0];
      (firstFocusable ?? dialogRef.current)?.focus();
    });
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }

      const focusable = focusableElements();
      if (focusable.length === 0) {
        event.preventDefault();
        dialogRef.current?.focus();
        return;
      }

      const first = focusable[0]!;
      const last = focusable[focusable.length - 1]!;
      const active = document.activeElement;
      if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      } else if (!dialogRef.current?.contains(active)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener("keydown", onKeyDown);
      siblingState.forEach(({ element, inert, ariaHidden }) => {
        if (!inert) {
          element.removeAttribute("inert");
        }
        if (ariaHidden === null) {
          element.removeAttribute("aria-hidden");
        } else {
          element.setAttribute("aria-hidden", ariaHidden);
        }
      });
      previous?.focus();
    };
  }, [onClose, open]);

  if (!open) {
    return null;
  }

  return createPortal(
    <div className="modal-backdrop" role="presentation" ref={backdropRef}>
      <div
        className="confirm-dialog shortcut-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="shortcut-help-title"
        ref={dialogRef}
        tabIndex={-1}
      >
        <h2 id="shortcut-help-title">Keyboard shortcuts</h2>
        <dl className="shortcut-list">
          {shortcuts.map((shortcut) => (
            <div key={shortcut.keys}>
              <dt>
                <kbd>{shortcut.keys}</kbd>
              </dt>
              <dd>{shortcut.action}</dd>
            </div>
          ))}
        </dl>
        <div className="form-actions">
          <button className="button button--primary" type="button" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
};
