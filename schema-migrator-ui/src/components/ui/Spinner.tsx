import type { CSSProperties } from "react";
import styles from "./Spinner.module.css";

interface SpinnerProps {
  size?: 16 | 20 | 24;
  label?: string;
}

export const Spinner = ({ size = 16, label = "Loading" }: SpinnerProps) => (
  <span aria-label={label} className={styles.spinner} role="status" style={{ "--spinner-size": `${size}px` } as CSSProperties}>
    <span className={styles.srOnly}>{label}</span>
  </span>
);
