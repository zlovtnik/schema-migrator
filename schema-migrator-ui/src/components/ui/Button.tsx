import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { Spinner } from "./Spinner";
import styles from "./Button.module.css";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md" | "lg";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leadingIcon?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      children,
      className,
      disabled = false,
      leadingIcon,
      loading = false,
      size = "md",
      type = "button",
      variant = "secondary",
      ...props
    },
    ref
  ) => {
    const isDisabled = disabled || loading;
    const classes = [styles.button, styles[variant], styles[size], className].filter(Boolean).join(" ");

    return (
      <button
        {...props}
        aria-busy={loading || undefined}
        aria-disabled={isDisabled || undefined}
        className={classes}
        disabled={isDisabled}
        ref={ref}
        type={type}
      >
        {loading ? <Spinner size={16} label="Working" /> : leadingIcon}
        {children}
      </button>
    );
  }
);

Button.displayName = "Button";
