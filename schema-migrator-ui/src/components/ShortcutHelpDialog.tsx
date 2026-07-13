import { useId } from "react";
import { Modal } from "./ui/Modal";

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
  const titleId = useId();
  return (
    <Modal className="shortcut-dialog" open={open} labelledBy={titleId} onClose={onClose}>
      {() => ({
        body: (
          <>
            <h2 id={titleId}>Keyboard shortcuts</h2>
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
          </>
        ),
        footer: (
          <button className="button button--primary" type="button" onClick={onClose}>
            Close
          </button>
        )
      })}
    </Modal>
  );
};
