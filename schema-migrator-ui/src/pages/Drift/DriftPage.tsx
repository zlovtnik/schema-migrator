import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";

export const DriftPage = () => (
  <section className="page">
    <header className="page-header">
      <div>
        <span className="eyebrow">Drift</span>
        <h1>Drift detection</h1>
        <p>Validation-backed drift results will appear here when exposed by the backend.</p>
      </div>
    </header>
    <EmptyState icon={<Icon source={ShieldCheckIcon} size={24} />} title="No drift feed configured">
      The current backend exposes validation reports by run. A drift feed can be connected here when available.
    </EmptyState>
  </section>
);
