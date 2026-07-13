import type { CSSProperties } from "react";

interface SpinnerProps {
  size?: 16 | 20 | 24;
  label?: string;
}

export const Spinner = ({ size = 16, label = "Loading" }: SpinnerProps) => (
  <span
    aria-label={label}
    className="ui-spinner"
    role="status"
    style={{ "--spinner-size": `${size}px` } as CSSProperties}
  >
    <span className="sr-only">{label}</span>
  </span>
);
