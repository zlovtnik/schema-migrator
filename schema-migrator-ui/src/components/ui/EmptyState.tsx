import type { ReactNode } from "react";

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}

export const EmptyState = ({ action, children, icon, title }: EmptyStateProps) => (
  <div className="ui-empty-state">
    {icon ? <div className="ui-empty-state__icon">{icon}</div> : null}
    <strong className="ui-empty-state__title">{title}</strong>
    {children ? <div className="ui-empty-state__body">{children}</div> : null}
    {action}
  </div>
);
