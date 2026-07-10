import { ClockIcon } from "@phosphor-icons/react/dist/csr/Clock";
import { Icon } from "./ui/Icon";

export interface MigrationTimelineItem {
  id: string;
  label: string;
  detail?: string | undefined;
  timestamp?: string | undefined;
}

interface MigrationTimelineProps {
  items: MigrationTimelineItem[];
}

export const MigrationTimeline = ({ items }: MigrationTimelineProps) => {
  if (items.length === 0) {
    return <div className="empty-state">No apply history is available for this object.</div>;
  }

  return (
    <ol className="migration-timeline">
      {items.map((item) => (
        <li key={item.id}>
          <Icon source={ClockIcon} size={16} />
          <div>
            <strong title={item.label}>{item.label}</strong>
            {item.detail ? <span title={item.detail}>{item.detail}</span> : null}
            {item.timestamp ? (
              <time dateTime={item.timestamp} title={new Date(item.timestamp).toLocaleString()}>
                {new Date(item.timestamp).toLocaleString()}
              </time>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  );
};
