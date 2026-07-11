import { useCallback, useEffect, useMemo, useState, type SyntheticEvent } from "react";
import { BracketsCurlyIcon } from "@phosphor-icons/react/dist/csr/BracketsCurly";
import { CopyIcon } from "@phosphor-icons/react/dist/csr/Copy";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { FolderOpenIcon } from "@phosphor-icons/react/dist/csr/FolderOpen";
import { GitBranchIcon } from "@phosphor-icons/react/dist/csr/GitBranch";
import { TableIcon } from "@phosphor-icons/react/dist/csr/Table";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { TreeStructureIcon } from "@phosphor-icons/react/dist/csr/TreeStructure";
import { PageHeader } from "../../components/PageHeader";
import { TargetSelector } from "../../components/TargetSelector";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon, type IconSource } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { getRepoSyncStatus, triggerRepoSync, type RepoSyncResult, type RepoSyncStatus } from "../../api/repoSync";
import {
  clearSqlFiles,
  getSqlFileStatus,
  listSqlFiles,
  type SqlFileEntry,
  type SqlFileStatus
} from "../../api/sqlFiles";
import { useSelectedTargetId } from "../../hooks/useSelectedTarget";
import { useSession } from "../../hooks/useSession";
import { useCreateSnapshot } from "../../hooks/useSnapshots";
import { useTarget } from "../../hooks/useTargets";

const ExpandedFoldersStorageKey = "schemaMigrator.sqlFiles.expandedFolders";

type FolderGroup = {
  folder: string;
  files: SqlFileEntry[];
};

const readExpandedFolders = (): Record<string, boolean> => {
  try {
    const stored = window.localStorage.getItem(ExpandedFoldersStorageKey);
    if (!stored) return {};
    const parsed = JSON.parse(stored) as unknown;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return Object.fromEntries(
      Object.entries(parsed).filter((entry): entry is [string, boolean] => typeof entry[1] === "boolean")
    );
  } catch {
    return {};
  }
};

const groupSqlFiles = (files: SqlFileEntry[]): FolderGroup[] => {
  const groups = new Map<string, SqlFileEntry[]>();
  for (const file of files) {
    const folderFiles = groups.get(file.folder) ?? [];
    folderFiles.push(file);
    groups.set(file.folder, folderFiles);
  }

  return Array.from(groups, ([folder, folderFiles]) => ({
    folder,
    files: [...folderFiles].sort((a, b) => a.filename.localeCompare(b.filename))
  })).sort((a, b) => a.folder.localeCompare(b.folder));
};

const filterGroups = (groups: FolderGroup[], query: string): FolderGroup[] => {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) return groups;

  return groups
    .map((group) => ({
      ...group,
      files: group.files.filter((file) => {
        const searchable = `${group.folder} ${file.filename} ${file.path}`.toLowerCase();
        return searchable.includes(normalizedQuery);
      })
    }))
    .filter((group) => group.files.length > 0);
};

const folderIconSource = (folder: string): IconSource => {
  const normalizedFolder = folder.toLowerCase();
  if (normalizedFolder.includes("oracle") || normalizedFolder.includes("postgres")) return DatabaseIcon;
  if (normalizedFolder.includes("table")) return TableIcon;
  if (normalizedFolder.includes("view")) return EyeIcon;
  if (normalizedFolder.includes("function") || normalizedFolder.includes("type")) return BracketsCurlyIcon;
  if (normalizedFolder.includes("index") || normalizedFolder.includes("schema")) return TreeStructureIcon;
  return FolderOpenIcon;
};

const folderDialect = (folder: string): "Oracle" | "Postgres" =>
  folder.toLowerCase().includes("oracle") ? "Oracle" : "Postgres";

const SqlFilesPage = () => {
  const selectedTarget = useSelectedTargetId();
  const targetQuery = useTarget(selectedTarget ?? undefined);
  const { canMutate } = useSession();
  const createSnapshot = useCreateSnapshot();
  const [status, setStatus] = useState<SqlFileStatus | null>(null);
  const [files, setFiles] = useState<SqlFileEntry[]>([]);
  const [repoStatus, setRepoStatus] = useState<RepoSyncStatus | null>(null);
  const [syncResult, setSyncResult] = useState<RepoSyncResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [expandedFolders, setExpandedFolders] = useState<Record<string, boolean>>(readExpandedFolders);
  const [copyStatus, setCopyStatus] = useState<string | null>(null);
  const effectiveTargetId = selectedTarget ?? targetQuery.data?.id ?? repoStatus?.target_id ?? null;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [s, f, repo] = await Promise.all([
        getSqlFileStatus(),
        listSqlFiles(),
        selectedTarget ? getRepoSyncStatus(selectedTarget) : Promise.resolve(null)
      ]);
      setStatus(s);
      setFiles(f.files);
      setRepoStatus(repo);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load SQL files");
    } finally {
      setLoading(false);
    }
  }, [selectedTarget]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    setSyncResult(null);
  }, [selectedTarget]);

  useEffect(() => {
    try {
      window.localStorage.setItem(ExpandedFoldersStorageKey, JSON.stringify(expandedFolders));
    } catch {
      // Persisted expansion state is a convenience; ignore unavailable storage.
    }
  }, [expandedFolders]);

  const groupedFiles = useMemo(() => groupSqlFiles(files), [files]);
  const filteredGroups = useMemo(() => filterGroups(groupedFiles, query), [groupedFiles, query]);
  const visibleFileCount = useMemo(
    () => filteredGroups.reduce((total, group) => total + group.files.length, 0),
    [filteredGroups]
  );
  const visibleFolders = useMemo(() => filteredGroups.map((group) => group.folder), [filteredGroups]);
  const hasQuery = query.trim().length > 0;
  const allVisibleExpanded =
    visibleFolders.length > 0 && visibleFolders.every((folder) => expandedFolders[folder] ?? false);

  useEffect(() => {
    if (!hasQuery) return;
    setExpandedFolders((current) => {
      let changed = false;
      const next = { ...current };
      for (const folder of visibleFolders) {
        if (!next[folder]) {
          next[folder] = true;
          changed = true;
        }
      }
      return changed ? next : current;
    });
  }, [hasQuery, visibleFolders]);

  const setVisibleFoldersExpanded = (expanded: boolean) => {
    setExpandedFolders((current) => {
      const next = { ...current };
      for (const folder of visibleFolders) {
        next[folder] = expanded;
      }
      return next;
    });
  };

  const handleFolderToggle = (folder: string, event: SyntheticEvent<HTMLDetailsElement>) => {
    const isOpen = event.currentTarget.open;
    setExpandedFolders((current) => ({ ...current, [folder]: isOpen }));
  };

  const handleCopyHash = async (hash: string, filename: string) => {
    try {
      await navigator.clipboard.writeText(hash);
      setCopyStatus(`Hash copied for ${filename}`);
    } catch {
      setError("Could not copy the hash to the clipboard");
    }
  };

  const handleSyncNow = async () => {
    if (!canMutate || !effectiveTargetId) return;
    setSyncing(true);
    setError(null);
    setSuccess(null);
    setCopyStatus(null);
    setSyncResult(null);

    try {
      const result = await triggerRepoSync(effectiveTargetId);
      setSyncResult(result);
      setSuccess(`Synced commit ${result.commit_sha.slice(0, 12)}`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Repository sync failed");
    } finally {
      setSyncing(false);
    }
  };

  const handleClear = async () => {
    if (!canMutate) return;
    if (!window.confirm("Clear all synced SQL files? This will remove the loaded repository manifest.")) return;
    setLoading(true);
    setError(null);
    try {
      await clearSqlFiles();
      setSuccess("SQL files cleared");
      setSyncResult(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to clear SQL files");
    } finally {
      setLoading(false);
    }
  };

  const handleCreateSnapshot = () => {
    if (!canMutate || !effectiveTargetId) {
      return;
    }
    setError(null);
    setSuccess(null);
    createSnapshot.mutate(
      { target_id: effectiveTargetId },
      {
        onSuccess: (snapshot) => setSuccess(`Created snapshot ${snapshot.label}`),
        onError: (err) => setError(err instanceof Error ? err.message : "Failed to create snapshot")
      }
    );
  };

  return (
    <section className="page">
      <PageHeader
        eyebrow="SQL files"
        title="SQL manifest management"
        description="Sync the selected target's Git repository SQL tree into MongoDB for schema comparison and drift detection."
      />

      <div className="sql-files-toolbar">
        <div className="sql-files-upload-area">
          <div className="repo-sync-summary">
            <span className="field-label">Repository</span>
            <strong>{targetQuery.data?.repo_url ?? repoStatus?.repo_url ?? "No target selected"}</strong>
            <span className="repo-sync-meta">
              <Icon source={GitBranchIcon} size={16} />
              {targetQuery.data?.repo_branch ?? repoStatus?.repo_branch ?? "main"} /{" "}
              {targetQuery.data?.repo_sql_path ?? repoStatus?.repo_sql_path ?? "sql"}
            </span>
          </div>
          <button
            className="button button--primary"
            type="button"
            onClick={handleSyncNow}
            disabled={!canMutate || !effectiveTargetId || syncing}
            title={
              !canMutate
                ? "Viewer role cannot sync SQL files"
                : !effectiveTargetId
                  ? "Select a target before syncing"
                  : undefined
            }
          >
            <Icon source={GitBranchIcon} size={16} />
            {syncing ? "Syncing" : "Sync now"}
          </button>
        </div>

        <div className="row-actions">
          <TargetSelector />
          <button
            className="button button--secondary"
            type="button"
            onClick={handleCreateSnapshot}
            disabled={!canMutate || !effectiveTargetId || loading || syncing || createSnapshot.isPending}
            title={
              !canMutate
                ? "Viewer role cannot create snapshots"
                : !effectiveTargetId
                  ? "Select a target before creating a snapshot"
                  : undefined
            }
          >
            <Icon source={GitBranchIcon} size={16} />
            {createSnapshot.isPending ? "Creating" : "Create snapshot"}
          </button>
          {status?.loaded ? (
            <button
              className="button button--danger"
              type="button"
              onClick={handleClear}
              disabled={loading || syncing || !canMutate}
              title={canMutate ? undefined : "Viewer role cannot clear SQL files"}
            >
              <Icon source={TrashIcon} size={16} />
              Clear all
            </button>
          ) : null}
        </div>
      </div>

      {error ? (
        <div className="status-banner status-banner--error" role="alert">
          {error}
        </div>
      ) : null}

      {success ? (
        <div className="status-banner" role="status">
          {success}
        </div>
      ) : null}

      {loading ? <Skeleton rows={6} label="Loading SQL files status" /> : null}
      {syncing ? <Skeleton rows={3} label="Syncing repository SQL files" /> : null}

      {!loading && status && !status.loaded ? (
        <EmptyState icon={<Icon source={GitBranchIcon} size={24} />} title="No SQL files synced">
          Select a target and sync its configured repository SQL path to load the manifest for schema comparison and
          drift detection.
        </EmptyState>
      ) : null}

      {!loading && status?.loaded ? (
        <div className="sql-files-summary">
          <div className="summary-card">
            <span className="field-label">Total files</span>
            <strong>{status.file_count}</strong>
          </div>
          <div className="summary-card">
            <span className="field-label">Folders</span>
            <strong>{status.folders.length}</strong>
          </div>
          <div className="summary-card">
            <span className="field-label">Last sync</span>
            <strong>{repoStatus?.last_synced_commit ? repoStatus.last_synced_commit.slice(0, 12) : "Never"}</strong>
          </div>
          <div className="summary-card">
            <span className="field-label">Remote drift</span>
            <strong>{repoStatus?.drift ? "Yes" : "No"}</strong>
          </div>
        </div>
      ) : null}

      {syncResult ? (
        <div className="sql-files-summary">
          <div className="summary-card">
            <span className="field-label">Added</span>
            <strong>{syncResult.added}</strong>
          </div>
          <div className="summary-card">
            <span className="field-label">Changed</span>
            <strong>{syncResult.changed}</strong>
          </div>
          <div className="summary-card">
            <span className="field-label">Removed</span>
            <strong>{syncResult.removed}</strong>
          </div>
          <div className="summary-card">
            <span className="field-label">Unchanged</span>
            <strong>{syncResult.unchanged}</strong>
          </div>
        </div>
      ) : null}

      {!loading && groupedFiles.length > 0 ? (
        <>
          <div className="sql-files-search-row">
            <label className="sql-files-search-field">
              <span className="sr-only">Filter SQL files</span>
              <input
                aria-label="Filter SQL files"
                type="search"
                placeholder="Filter files, folders, or paths"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <button
              className="button button--ghost"
              type="button"
              onClick={() => setVisibleFoldersExpanded(!allVisibleExpanded)}
              disabled={visibleFolders.length === 0}
            >
              <Icon source={TreeStructureIcon} size={16} />
              {allVisibleExpanded ? "Collapse all" : "Expand all"}
            </button>
            {hasQuery ? (
              <button className="button button--secondary" type="button" onClick={() => setQuery("")}>
                Clear
              </button>
            ) : null}
            <span className="sql-files-filter-count" aria-live="polite">
              {hasQuery ? `${visibleFileCount} of ${files.length} files` : `${files.length} files`}
            </span>
          </div>

          {copyStatus ? (
            <div className="sql-files-copy-status" role="status">
              {copyStatus}
            </div>
          ) : null}

          {filteredGroups.length > 0 ? (
            <div className="sql-files-listing">
              {filteredGroups.map((group) => {
                const isExpanded = expandedFolders[group.folder] ?? false;
                const dialect = folderDialect(group.folder);
                return (
                  <details
                    key={group.folder}
                    className="sql-folder-group"
                    open={isExpanded}
                    onToggle={(event) => handleFolderToggle(group.folder, event)}
                  >
                    <summary className="sql-folder-summary">
                      <span className="sql-folder-title">
                        <Icon source={folderIconSource(group.folder)} size={16} weight="bold" />
                        <strong>{group.folder}</strong>
                        <span className={`folder-dialect folder-dialect--${dialect.toLowerCase()}`}>{dialect}</span>
                      </span>
                      <span className="file-count">
                        {group.files.length} file{group.files.length !== 1 ? "s" : ""}
                      </span>
                    </summary>
                    {isExpanded ? (
                      <ul className="sql-file-list">
                        {group.files.map((file) => (
                          <li key={file.path} className="sql-file-item">
                            <span className="sql-file-name">
                              <Icon source={FileSqlIcon} size={16} />
                              <code>{file.filename}</code>
                            </span>
                            <span className="sql-file-meta">
                              {file.sha256 ? (
                                <button
                                  className="hash-chip"
                                  type="button"
                                  title={file.sha256}
                                  aria-label={`Copy SHA-256 hash for ${file.filename}`}
                                  onClick={() => handleCopyHash(file.sha256, file.filename)}
                                >
                                  <Icon source={CopyIcon} size={16} />
                                  {file.sha256.slice(0, 8)}
                                </button>
                              ) : (
                                <span className="hash-chip hash-chip--empty">No hash</span>
                              )}
                            </span>
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </details>
                );
              })}
            </div>
          ) : (
            <EmptyState icon={<Icon source={FileSqlIcon} size={24} />} title="No SQL files match">
              Adjust the filter to find a file, folder, or path in the synced manifest.
            </EmptyState>
          )}
        </>
      ) : null}
    </section>
  );
};

export default SqlFilesPage;
