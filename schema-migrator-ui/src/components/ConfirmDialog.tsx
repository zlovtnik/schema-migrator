import { useEffect, useId, useState } from "react";
import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { Icon } from "./ui/Icon";
import { Modal } from "./ui/Modal";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string | undefined;
  requireText?: string | undefined;
  destructive?: boolean | undefined;
  busy?: boolean | undefined;
  onCancel: () => void;
  onConfirm: () => void;
}

export const ConfirmDialog = ({
  open,
  title,
  message,
  confirmLabel = "Confirm",
  requireText,
  destructive = false,
  busy = false,
  onCancel,
  onConfirm
}: ConfirmDialogProps) => {
  const [typed, setTyped] = useState("");
  const titleId = useId();
  const descriptionId = useId();
  const confirmationId = useId();

  useEffect(() => {
    if (!open) setTyped("");
  }, [open]);

  const disabled = busy || (requireText ? typed !== requireText : false);

  return (
    <Modal open={open} labelledBy={titleId} describedBy={descriptionId} onClose={onCancel}>
      {() => ({
        body: (
          <>
            <div className={destructive ? "dialog-icon dialog-icon--danger" : "dialog-icon"}>
              <Icon source={WarningIcon} size={20} weight="bold" />
            </div>
            <h2 id={titleId}>{title}</h2>
            <p id={descriptionId}>{message}</p>
            {requireText ? (
              <label htmlFor={confirmationId}>
                Type <strong>{requireText}</strong> to confirm
                <input
                  autoComplete="off"
                  autoFocus
                  id={confirmationId}
                  name="typed-confirmation"
                  value={typed}
                  onChange={(event) => setTyped(event.target.value)}
                />
              </label>
            ) : null}
          </>
        ),
        footer: (
          <>
            <button className="button button--ghost" type="button" onClick={onCancel}>
              Cancel
            </button>
            <button
              className={destructive ? "button button--danger" : "button button--primary"}
              type="button"
              onClick={onConfirm}
              disabled={disabled}
            >
              {busy ? "Working" : confirmLabel}
            </button>
          </>
        )
      })}
    </Modal>
  );
};
