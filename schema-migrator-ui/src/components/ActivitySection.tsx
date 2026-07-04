import { ActivityTable } from "./ActivityTable";
import type { AuditEvent } from "../types";

interface ActivitySectionProps {
  events: AuditEvent[];
  isLoading: boolean;
  empty: string;
}

export const ActivitySection = ({ events, isLoading, empty }: ActivitySectionProps) => (
  <section className="section-block">
    <h2>Activity</h2>
    {isLoading ? <div className="empty-state">Loading activity...</div> : <ActivityTable events={events} empty={empty} />}
  </section>
);
