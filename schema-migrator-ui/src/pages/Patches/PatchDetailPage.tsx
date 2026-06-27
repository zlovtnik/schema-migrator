import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { Trash2 } from "lucide-react";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { StatusBadge } from "../../components/StatusBadge";
import { useDeletePatch, usePatch } from "../../hooks/usePatches";

export const PatchDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: patch, isLoading, error } = usePatch(id);
  const deletePatch = useDeletePatch();
  const [confirmOpen, setConfirmOpen] = useState(false);

  if (isLoading) {
    return <div className="page empty-state">Loading patch...</div>;
  }

  if (error || !patch) {
    return <div className="page status-banner status-banner--error">Patch could not be loaded.</div>;
  }

  const confirmDelete = () => {
    deletePatch.mutate(patch.id, {
      onSuccess: () => navigate(`/patches?target=${patch.target_id}`)
    });
  };

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Patch detail</span>
          <h1>{patch.version}</h1>
          <p>{patch.label}</p>
        </div>
        <div className="row-actions">
          <StatusBadge status={patch.status} />
          {patch.status === "pending" ? (
            <button className="button button--danger" type="button" onClick={() => setConfirmOpen(true)} disabled={deletePatch.isPending}>
              <Trash2 size={16} aria-hidden="true" />
              Delete patch
            </button>
          ) : null}
        </div>
      </header>

      <div className="detail-grid">
        <div>
          <span className="field-label">Target</span>
          <strong>{patch.target_id}</strong>
        </div>
        <div>
          <span className="field-label">Related runs</span>
          <Link to={`/runs?target=${patch.target_id}`}>View run history</Link>
        </div>
      </div>

      <div className="table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Order</th>
              <th>Filename</th>
              <th>Checksum</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {[...patch.scripts].sort((a, b) => a.order - b.order).map((script) => (
              <tr key={script.id}>
                <td>{script.order}</td>
                <td>{script.filename}</td>
                <td>
                  <code>{script.checksum}</code>
                </td>
                <td>
                  <StatusBadge status={script.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Delete patch"
        message={`Delete ${patch.version}? This cannot be undone.`}
        confirmLabel="Delete"
        destructive
        busy={deletePatch.isPending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={confirmDelete}
      />
    </section>
  );
};
