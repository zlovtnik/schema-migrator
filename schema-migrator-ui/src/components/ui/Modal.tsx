import { useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useFocusTrap } from "../../hooks/useFocusTrap";

export interface ModalRenderContext {
  close: () => void;
}

export interface ModalContent {
  body: ReactNode;
  footer?: ReactNode;
}

interface ModalProps {
  open: boolean;
  labelledBy: string;
  onClose: () => void;
  children: (context: ModalRenderContext) => ModalContent;
  className?: string;
  describedBy?: string;
}

export const Modal = ({ children, className, describedBy, labelledBy, onClose, open }: ModalProps) => {
  const backdropRef = useRef<HTMLDivElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  useFocusTrap({ open, containerRef: dialogRef, portalRef: backdropRef, onEscape: onClose });

  if (!open) return null;
  const content = children({ close: onClose });

  return createPortal(
    <div className="modal-backdrop" role="presentation" ref={backdropRef}>
      <div
        className={["confirm-dialog", "glass-surface", className].filter(Boolean).join(" ")}
        role="dialog"
        aria-describedby={describedBy}
        aria-labelledby={labelledBy}
        aria-modal="true"
        ref={dialogRef}
        tabIndex={-1}
      >
        <div className="modal-body">{content.body}</div>
        {content.footer ? <div className="form-actions modal-footer">{content.footer}</div> : null}
      </div>
    </div>,
    document.body
  );
};
