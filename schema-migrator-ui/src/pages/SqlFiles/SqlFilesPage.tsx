import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type SyntheticEvent } from "react";
import { zipSync } from "fflate";
import { BracketsCurlyIcon } from "@phosphor-icons/react/dist/csr/BracketsCurly";
import { CopyIcon } from "@phosphor-icons/react/dist/csr/Copy";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { FolderOpenIcon } from "@phosphor-icons/react/dist/csr/FolderOpen";
import { TableIcon } from "@phosphor-icons/react/dist/csr/Table";
import { TrashIcon } from "@phosphor-icons/react/dist/csr/Trash";
import { TreeStructureIcon } from "@phosphor-icons/react/dist/csr/TreeStructure";
import { PageHeader } from "../../components/PageHeader";
import { EmptyState } from "../../components/ui/EmptyState";
import { Icon, type IconSource } from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/Skeleton";
import { clearSqlFiles, getSqlFileStatus, listSqlFiles, uploadSqlZip, type SqlFileEntry, type SqlFileStatus } from "../../api/sqlFiles";

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
  const [status, setStatus] = useState<SqlFileStatus | null>(null);
  const [files, setFiles] = useState<SqlFileEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [expandedFolders, setExpandedFolders] = useState<Record<string, boolean>>(readExpandedFolders);
  const [copyStatus, setCopyStatus] = useState<string | null>(null);
  const dirInputRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [s, f] = await Promise.all([getSqlFileStatus(), listSqlFiles()]);
      setStatus(s);
      setFiles(f.files);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load SQL files");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

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
  const allVisibleExpanded = visibleFolders.length > 0 && visibleFolders.every((folder) => expandedFolders[folder] ?? false);

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

  const handleDirectoryPick = async (e: ChangeEvent<HTMLInputElement>) => {
    const fileList = e.target.files;
    if (!fileList || fileList.length === 0) return;

    setUploading(true);
    setError(null);
    setSuccess(null);
    setCopyStatus(null);

    try {
      // Collect all .sql files from the directory tree
      const sqlFiles: { path: string; file: File }[] = [];
      for (let i = 0; i < fileList.length; i++) {
        const file = fileList.item(i);
        if (file && (file.name.endsWith(".sql") || file.name.endsWith(".SQL"))) {
          // webkitRelativePath gives us the full relative path like "extensions/001_pgcrypto.sql"
          sqlFiles.push({ path: file.webkitRelativePath, file });
        }
      }

      if (sqlFiles.length === 0) {
        setError("No .sql files found in the selected directory");
        setUploading(false);
        return;
      }

      // Create a JSZip-like structure manually using a simple zip library
      // We'll use the native CompressionStream API if available, or fall back
      const zipBlob = await createZip(sqlFiles);

      const result = await uploadSqlZip(zipBlob);
      setSuccess(`Uploaded ${result.uploaded} SQL files across folders: ${result.folders.join(", ")}`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
      // Reset the input so the same directory can be picked again
      if (dirInputRef.current) dirInputRef.current.value = "";
    }
  };

  const handleClear = async () => {
    if (!window.confirm("Clear all uploaded SQL files? This will revert to filesystem-based discovery.")) return;
    setLoading(true);
    setError(null);
    try {
      await clearSqlFiles();
      setSuccess("SQL files cleared");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to clear SQL files");
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="page">
      <PageHeader
        eyebrow="SQL files"
        title="SQL manifest management"
        description="Upload your SQL directory tree to use as the schema manifest. Files are stored in MongoDB as base64."
      />

      <div className="sql-files-toolbar">
        <div className="sql-files-upload-area">
          <input
            ref={dirInputRef}
            type="file"
            // @ts-expect-error webkitdirectory is a non-standard attribute
            webkitdirectory=""
            directory=""
            onChange={handleDirectoryPick}
            style={{ display: "none" }}
            id="sql-dir-picker"
          />
          <button className="button button--primary" type="button" onClick={() => dirInputRef.current?.click()}>
            <Icon source={FolderOpenIcon} size={16} />
            {uploading ? "Uploading..." : "Choose SQL directory"}
          </button>
          {uploading ? <span className="inline-result">Zipping and uploading...</span> : null}
        </div>

        {status?.loaded ? (
          <button className="button button--danger" type="button" onClick={handleClear} disabled={loading}>
            <Icon source={TrashIcon} size={16} />
            Clear all
          </button>
        ) : null}
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
      {uploading ? <Skeleton rows={3} label="Preparing SQL archive" /> : null}

      {!loading && status && !status.loaded ? (
        <EmptyState
          icon={<Icon source={FolderOpenIcon} size={24} />}
          title="No SQL files uploaded"
        >
          Select a directory containing your SQL migration files (extensions/, schemas/, tables/, etc.)
          to use as the schema manifest. The files will be stored in MongoDB and used for schema
          comparison and drift detection.
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
                      <span className="file-count">{group.files.length} file{group.files.length !== 1 ? "s" : ""}</span>
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
              Adjust the filter to find a file, folder, or path in the uploaded manifest.
            </EmptyState>
          )}
        </>
      ) : null}
    </section>
  );
};

export default SqlFilesPage;

async function createZip(files: { path: string; file: File }[]): Promise<Blob> {
  const zipWriter = new ZipWriter();
  for (const { path, file } of files) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    zipWriter.addFile(path, bytes);
  }
  return zipWriter.toBlob();
}

/**
 * Minimal wrapper around fflate for browser ZIP archive creation.
 */
export class ZipWriter {
  private files: Record<string, Uint8Array> = {};

  addFile(path: string, bytes: Uint8Array): void {
    this.files[path] = bytes;
  }

  toBlob(): Blob {
    return new Blob([this.toBytes()], { type: "application/zip" });
  }

  toBytes(): Uint8Array<ArrayBuffer> {
    const zipped = zipSync(this.files, { level: 0 });
    const bytes = new Uint8Array(zipped.byteLength);
    bytes.set(zipped);
    return bytes;
  }
}
