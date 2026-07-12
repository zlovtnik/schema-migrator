import type { HTMLAttributes, ReactNode } from "react";
import styles from "./Badge.module.css";

export type BadgeTone = "neutral" | "success" | "warning" | "danger" | "info";

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  icon?: ReactNode;
  mono?: boolean;
}

export const Badge = ({ children, className, icon, mono = false, tone = "neutral", ...props }: BadgeProps) => (
  <span
    {...props}
    className={[styles.badge, styles[tone], mono ? styles.mono : "", className].filter(Boolean).join(" ")}
  >
    {icon}
    {children}
  </span>
);
