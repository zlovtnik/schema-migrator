import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";

export const SchemaPage = () => (
  <section className="page">
    <header className="page-header">
      <div>
        <span className="eyebrow">Schema browser</span>
        <h1>Tracked objects</h1>
        <p>Schema object browsing is ready for a catalog endpoint.</p>
      </div>
    </header>
    <EmptyState icon={<Icon source={DatabaseIcon} size={24} />} title="Schema catalog endpoint unavailable">
      Add a backend endpoint for schema objects to populate tables, views, functions, indexes, triggers, and types here.
    </EmptyState>
  </section>
);
