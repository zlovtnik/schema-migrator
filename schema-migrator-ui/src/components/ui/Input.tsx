import { forwardRef, type InputHTMLAttributes, type ReactNode, useId } from "react";
import { XIcon } from "@phosphor-icons/react";
import { Icon } from "./Icon";
import styles from "./Input.module.css";

interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "prefix"> {
  label: string;
  error?: string;
  prefix?: ReactNode;
  suffix?: ReactNode;
  onClear?: () => void;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, id, label, name, onClear, prefix, suffix, value, "aria-describedby": ariaDescribedBy, ...props }, ref) => {
    const generatedId = useId();
    const inputId = id ?? generatedId;
    const errorId = error ? `${inputId}-error` : undefined;
    const describedBy = [ariaDescribedBy, errorId].filter(Boolean).join(" ") || undefined;

    return (
      <label className={[styles.field, className].filter(Boolean).join(" ")} htmlFor={inputId}>
        {label}
        <span className={styles.control}>
          {prefix ? <span className={styles.slot}>{prefix}</span> : null}
          <input
            {...props}
            aria-describedby={describedBy}
            aria-invalid={Boolean(error) || undefined}
            className={styles.input}
            id={inputId}
            name={name}
            ref={ref}
            value={value}
          />
          {onClear && value ? (
            <button aria-label={`Clear ${label}`} className={styles.clear} onClick={onClear} type="button">
              <Icon source={XIcon} size={16} />
            </button>
          ) : suffix ? (
            <span className={styles.slot}>{suffix}</span>
          ) : null}
        </span>
        {error ? (
          <span className={styles.error} id={errorId} role="alert">
            {error}
          </span>
        ) : null}
      </label>
    );
  }
);

Input.displayName = "Input";
