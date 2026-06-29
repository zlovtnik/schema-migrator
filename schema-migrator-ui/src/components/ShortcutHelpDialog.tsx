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

export const ShortcutHelpDialog = ({ open, onClose }: ShortcutHelpDialogProps) => {
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const frame = window.requestAnimationFrame(() => dialogRef.current?.focus());
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener("keydown", onKeyDown);
      previous?.focus();
    };
  }, [onClose, open]);

  if (!open) {
    return null;
  }

  return createPortal(
    <div className="modal-backdrop" role="presentation">
      <div className="confirm-dialog shortcut-dialog" role="dialog" aria-modal="true" aria-labelledby="shortcut-help-title" ref={dialogRef} tabIndex={-1}>
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
