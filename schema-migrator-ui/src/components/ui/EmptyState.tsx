import type { ReactNode } from "react";
import styles from "./EmptyState.module.css";

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}

export const EmptyState = ({ action, children, icon, title }: EmptyStateProps) => (
  <div className={styles.emptyState}>
    {icon ? <div className={styles.icon}>{icon}</div> : null}
    <strong className={styles.title}>{title}</strong>
    {children ? <div className={styles.body}>{children}</div> : null}
    {action}
  </div>
);
