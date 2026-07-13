import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { GitDiffIcon } from "@phosphor-icons/react/dist/csr/GitDiff";
import { GitBranchIcon } from "@phosphor-icons/react/dist/csr/GitBranch";
import { PageHeader } from "../../components/PageHeader";
import { TargetSelector } from "../../components/TargetSelector";
import { DataTable, type DataTableColumn } from "../../components/ui/DataTable";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { useMutationGuard } from "../../hooks/useMutationGuard";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { useCreateSnapshot, useSnapshots } from "../../hooks/useSnapshots";
import { useTarget } from "../../hooks/useTargets";
import type { Snapshot } from "../../types";
import { isOracleTarget } from "../../utils/dbKind";

export const SnapshotListPage = () => {
  const selectedTarget = useSelectedTargetId();
  const { canMutate } = useSession();
  const mutationGuard = useMutationGuard(canMutate);
  const { data: snapshots = [], isLoading, error } = useSnapshots(selectedTarget);
  const targetQuery = useTarget(selectedTarget ?? undefined);
  const createSnapshot = useCreateSnapshot();
  const [compareBySnapshot, setCompareBySnapshot] = useState<Record<string, string>>({});

  const sortedSnapshots = useMemo(
    () => [...snapshots].sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at)),
    [snapshots]
  );
  const oracleTarget = isOracleTarget(targetQuery.data?.jdbc_url);

  const create = () => {
    if (!canMutate || !selectedTarget || oracleTarget) {
      return;
    }
    createSnapshot.mutate({ target_id: selectedTarget });
  };
  const createGuard = mutationGuard(
    "Viewer role cannot create snapshots",
    !selectedTarget || oracleTarget || createSnapshot.isPending
  );

  const columns = useMemo<DataTableColumn<Snapshot>[]>(
    () => [
      {
        id: "label",
        header: "Label",
        sortValue: (snapshot) => snapshot.label,
        cell: (snapshot) => <Link to={`/snapshots/${snapshot.id}`}>{snapshot.label}</Link>
      },
      {
        id: "created",
        header: "Created",
        sortValue: (snapshot) => snapshot.created_at,
        cell: (snapshot) => <time dateTime={snapshot.created_at}>{new Date(snapshot.created_at).toLocaleString()}</time>
      },
      {
        id: "created_by",
        header: "Created by",
        sortValue: (snapshot) => snapshot.created_by,
        cell: (snapshot) => snapshot.created_by
      },
      {
        id: "files",
        header: "Files",
        sortValue: (snapshot) => snapshot.file_count,
        cell: (snapshot) => snapshot.file_count
      },
      {
        id: "actions",
        header: "Actions",
        cell: (snapshot) => {
          const options = sortedSnapshots.filter((candidate) => candidate.id !== snapshot.id);
          const selectedOther = compareBySnapshot[snapshot.id] ?? options[0]?.id ?? "";
          return (
            <div className="row-actions">
              <select
                aria-label={`Diff ${snapshot.label} against snapshot`}
                className="table-select"
                value={selectedOther}
                onChange={(event) =>
                  setCompareBySnapshot((current) => ({ ...current, [snapshot.id]: event.target.value }))
                }
                disabled={options.length === 0}
              >
                {options.length === 0 ? <option value="">No comparison</option> : null}
                {options.map((candidate) => (
                  <option value={candidate.id} key={candidate.id}>
                    {candidate.label}
                  </option>
                ))}
              </select>
              {selectedOther ? (
                <Link
                  className="button button--secondary button--small"
                  to={`/snapshots/${snapshot.id}/diff/${selectedOther}`}
                >
                  <Icon source={GitDiffIcon} size={16} />
                  Diff
                </Link>
              ) : (
                <button className="button button--secondary button--small" type="button" disabled>
                  <Icon source={GitDiffIcon} size={16} />
                  Diff
                </button>
              )}
            </div>
          );
        }
      }
    ],
    [compareBySnapshot, sortedSnapshots]
  );

  return (
    <section className="page">
      <PageHeader
        eyebrow="Version control"
        title="Snapshots"
        description="Point-in-time SQL manifest captures for the selected target."
        actions={
          <>
            <TargetSelector />
            <button
              className="button button--primary"
              type="button"
              onClick={create}
              disabled={createGuard.disabled}
              title={
                !canMutate
                  ? createGuard.title
                  : oracleTarget
                    ? "Snapshots are Postgres only for schema targets"
                    : !selectedTarget
                      ? "Select a target before creating a snapshot"
                      : undefined
              }
            >
              <Icon source={GitBranchIcon} size={16} weight="bold" />
              {createSnapshot.isPending ? "Creating" : "Create snapshot"}
            </button>
          </>
        }
      />

      {!selectedTarget ? (
        <EmptyState icon={<Icon source={GitBranchIcon} size={24} />} title="Select a target">
          Choose a target to view or create snapshots.
        </EmptyState>
      ) : null}

      {selectedTarget && isLoading ? <Skeleton rows={6} label="Loading snapshots" /> : null}

      {selectedTarget && error ? (
        <div className="status-banner status-banner--error" role="alert">
          Snapshots could not be loaded.
        </div>
      ) : null}

      {selectedTarget && oracleTarget ? (
        <div className="status-banner">
          Snapshots are Postgres only for schema targets. Use Oracle connection checks and migration runs for Oracle
          targets.
        </div>
      ) : null}

      {selectedTarget && !isLoading ? (
        <DataTable
          caption="Snapshots"
          columns={columns}
          rows={sortedSnapshots}
          rowKey={(snapshot) => snapshot.id}
          empty="No snapshots have been captured for this target."
        />
      ) : null}
    </section>
  );
};
