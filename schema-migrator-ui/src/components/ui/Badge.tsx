import type { HTMLAttributes, ReactNode } from "react";

export type BadgeTone = "neutral" | "success" | "warning" | "danger" | "info";

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  icon?: ReactNode;
  mono?: boolean;
}

export const Badge = ({ children, className, icon, mono = false, tone = "neutral", ...props }: BadgeProps) => (
  <span
    {...props}
    className={["ui-badge", `ui-badge--${tone}`, mono ? "ui-badge--mono" : "", className].filter(Boolean).join(" ")}
  >
    {icon}
    {children}
  </span>
);
