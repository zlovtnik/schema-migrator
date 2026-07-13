import { BracketsCurlyIcon } from "@phosphor-icons/react/dist/csr/BracketsCurly";
import { CirclesThreePlusIcon } from "@phosphor-icons/react/dist/csr/CirclesThreePlus";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { FolderOpenIcon } from "@phosphor-icons/react/dist/csr/FolderOpen";
import { TableIcon } from "@phosphor-icons/react/dist/csr/Table";
import { TreeStructureIcon } from "@phosphor-icons/react/dist/csr/TreeStructure";
import { LightningIcon } from "@phosphor-icons/react/dist/csr/Lightning";
import { ListNumbersIcon } from "@phosphor-icons/react/dist/csr/ListNumbers";
import type { SqlFileEntry } from "../api/sqlFiles";
import type { ObjectType } from "../types";
import type { IconSource } from "./ui/Icon";

export type FolderGroup = {
  folder: string;
  files: SqlFileEntry[];
};

export const groupSqlFiles = (files: SqlFileEntry[]): FolderGroup[] => {
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

export const filterGroups = (groups: FolderGroup[], query: string): FolderGroup[] => {
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

export const folderIconSource = (folder: string, objectType?: ObjectType): IconSource => {
  if (objectType === "table") return TableIcon;
  if (objectType === "view" || objectType === "materialized_view") return EyeIcon;
  if (objectType === "function" || objectType === "procedure" || objectType === "type") return BracketsCurlyIcon;
  if (objectType === "trigger") return LightningIcon;
  if (objectType === "index" || objectType === "sequence") return ListNumbersIcon;
  if (objectType === "schema") return TreeStructureIcon;
  if (objectType === "extension") return CirclesThreePlusIcon;
  const normalizedFolder = folder.toLowerCase();
  if (normalizedFolder.includes("oracle") || normalizedFolder.includes("postgres")) return DatabaseIcon;
  if (normalizedFolder.includes("table")) return TableIcon;
  if (normalizedFolder.includes("view")) return EyeIcon;
  if (normalizedFolder.includes("function") || normalizedFolder.includes("type")) return BracketsCurlyIcon;
  if (normalizedFolder.includes("index") || normalizedFolder.includes("schema")) return TreeStructureIcon;
  return FolderOpenIcon;
};

export const folderDialect = (folder: string): "Oracle" | "Postgres" =>
  folder.toLowerCase().includes("oracle") ? "Oracle" : "Postgres";
