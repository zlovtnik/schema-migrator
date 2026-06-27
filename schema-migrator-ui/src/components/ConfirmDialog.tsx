import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AlertTriangle } from "lucide-react";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  requireText?: string;
  destructive?: boolean;
  busy?: boolean;
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
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) {
      setTyped("");
      return;
    }

    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusInitial = window.requestAnimationFrame(() => {
      const focusable = getFocusableElements(dialogRef.current);
      focusable[0]?.focus();
    });

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onCancel();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }

      const focusable = getFocusableElements(dialogRef.current);
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.cancelAnimationFrame(focusInitial);
      window.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, [onCancel, open]);

  if (!open) {
    return null;
  }

  const disabled = busy || (requireText ? typed !== requireText : false);

  return createPortal(
    <div className="modal-backdrop" role="presentation">
      <div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title" ref={dialogRef}>
        <div className={destructive ? "dialog-icon dialog-icon--danger" : "dialog-icon"}>
          <AlertTriangle size={20} aria-hidden="true" />
        </div>
        <h2 id="confirm-title">{title}</h2>
        <p>{message}</p>
        {requireText ? (
          <label>
            Type <strong>{requireText}</strong> to confirm
            <input value={typed} onChange={(event) => setTyped(event.target.value)} autoFocus />
          </label>
        ) : null}
        <div className="form-actions">
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
        </div>
      </div>
    </div>,
    document.body
  );
};

const getFocusableElements = (root: HTMLElement | null): HTMLElement[] => {
  if (!root) {
    return [];
  }
  return Array.from(
    root.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
    )
  ).filter((element) => !element.hasAttribute("hidden"));
};
