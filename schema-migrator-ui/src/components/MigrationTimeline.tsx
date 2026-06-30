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
    return <div className="empty-state">No migration history is available for this object.</div>;
  }

  return (
    <ol className="migration-timeline">
      {items.map((item) => (
        <li key={item.id}>
          <Icon source={ClockIcon} size={16} />
          <div>
            <strong>{item.label}</strong>
            {item.detail ? <span>{item.detail}</span> : null}
            {item.timestamp ? <time dateTime={item.timestamp}>{new Date(item.timestamp).toLocaleString()}</time> : null}
          </div>
        </li>
      ))}
    </ol>
  );
};
