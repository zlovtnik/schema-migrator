import { useEffect, useMemo, useState, type SyntheticEvent } from "react";
import { FileSqlIcon } from "@phosphor-icons/react/dist/csr/FileSql";
import { TreeStructureIcon } from "@phosphor-icons/react/dist/csr/TreeStructure";
import type { SqlFileEntry } from "../api/sqlFiles";
import { filterGroups, folderDialect, folderIconSource, groupSqlFiles } from "./sqlFileTree";
import { Icon } from "./ui/Icon";

const ExpandedFoldersStorageKey = "schemaMigrator.sqlFilePicker.expandedFolders";

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

interface SqlFileTargetPickerProps {
  files: SqlFileEntry[];
  selectedPaths: string[];
  onSelectedPathsChange: (paths: string[]) => void;
  disabled?: boolean;
}

export const SqlFileTargetPicker = ({
  disabled = false,
  files,
  onSelectedPathsChange,
  selectedPaths
}: SqlFileTargetPickerProps) => {
  const [query, setQuery] = useState("");
  const [expandedFolders, setExpandedFolders] = useState<Record<string, boolean>>(readExpandedFolders);

  const selected = useMemo(() => new Set(selectedPaths), [selectedPaths]);
  const groupedFiles = useMemo(() => groupSqlFiles(files), [files]);
  const filteredGroups = useMemo(() => filterGroups(groupedFiles, query), [groupedFiles, query]);
  const visibleFolders = useMemo(() => filteredGroups.map((group) => group.folder), [filteredGroups]);
  const visiblePaths = useMemo(
    () => filteredGroups.flatMap((group) => group.files.map((file) => file.path)),
    [filteredGroups]
  );
  const visibleFileCount = visiblePaths.length;
  const hasQuery = query.trim().length > 0;
  const allVisibleExpanded =
    visibleFolders.length > 0 && visibleFolders.every((folder) => expandedFolders[folder] ?? false);
  const allVisibleSelected = visiblePaths.length > 0 && visiblePaths.every((path) => selected.has(path));

  useEffect(() => {
    try {
      window.localStorage.setItem(ExpandedFoldersStorageKey, JSON.stringify(expandedFolders));
    } catch {
      // Persisted expansion state is a convenience; ignore unavailable storage.
    }
  }, [expandedFolders]);

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

  const togglePath = (path: string, checked: boolean) => {
    const next = checked ? [...selectedPaths, path] : selectedPaths.filter((selectedPath) => selectedPath !== path);
    onSelectedPathsChange(Array.from(new Set(next)));
  };

  const toggleVisible = () => {
    if (allVisibleSelected) {
      onSelectedPathsChange(selectedPaths.filter((path) => !visiblePaths.includes(path)));
      return;
    }
    onSelectedPathsChange(Array.from(new Set([...selectedPaths, ...visiblePaths])));
  };

  const handleFolderToggle = (folder: string, event: SyntheticEvent<HTMLDetailsElement>) => {
    const details = event.currentTarget;
    if (!details) return;
    const isOpen = details.open;
    setExpandedFolders((current) => ({ ...current, [folder]: isOpen }));
  };

  return (
    <div className="sql-file-picker">
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
        <button
          className="button button--secondary"
          type="button"
          onClick={toggleVisible}
          disabled={disabled || visiblePaths.length === 0}
        >
          {allVisibleSelected ? "Clear visible" : "Select visible"}
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
                      <li key={file.path} className="sql-file-item sql-file-item--selectable">
                        <label className="sql-file-checkbox">
                          <input
                            type="checkbox"
                            checked={selected.has(file.path)}
                            disabled={disabled}
                            onChange={(event) => togglePath(file.path, event.target.checked)}
                          />
                          <span className="sql-file-name">
                            <Icon source={FileSqlIcon} size={16} />
                            <code>{file.path}</code>
                          </span>
                        </label>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </details>
            );
          })}
        </div>
      ) : (
        <div className="empty-state">No SQL files match this filter.</div>
      )}
    </div>
  );
};
