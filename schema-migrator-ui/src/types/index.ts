import * as z from "zod";

export type Rfc3339Timestamp = string & { readonly __brand: "Rfc3339" };
export type Env = "production" | "staging" | "dev";
export type RunStatus = "pending" | "running" | "completed" | "failed" | "aborted";
export type PatchStatus = "pending" | "applied" | "failed" | "partial";
export type ScriptStatus = "pending" | "running" | "completed" | "failed" | "skipped";
export type Severity = "warning" | "error";
export type ObjectType =
  | "schema"
  | "extension"
  | "table"
  | "view"
  | "materialized_view"
  | "function"
  | "procedure"
  | "sequence"
  | "trigger"
  | "index"
  | "type"
  | "other";
export type ValidationStatus = "clean" | "warnings" | "errors";
export type DbKind = "postgres" | "oracle";
export type SchemaObjectStatus = "defined" | "in_sync" | "drift_detected" | "pending_migration" | "unknown";
export type DriftType = "missing_actual" | "untracked_actual" | "definition_changed" | "pending_or_failed_control";
export type SnapshotDiffType = "added" | "changed" | "removed";
export type UserRole = "admin" | "operator" | "viewer";

export const envOptions = ["production", "staging", "dev"] as const;
export const runStatusOptions = ["pending", "running", "completed", "failed", "aborted"] as const;
export const patchStatusOptions = ["pending", "applied", "failed", "partial"] as const;
export const scriptStatusOptions = ["pending", "running", "completed", "failed", "skipped"] as const;
export const severityOptions = ["warning", "error"] as const;
export const validationStatusOptions = ["clean", "warnings", "errors"] as const;
export const objectTypeOptions = [
  "schema",
  "extension",
  "table",
  "view",
  "materialized_view",
  "function",
  "procedure",
  "sequence",
  "trigger",
  "index",
  "type",
  "other"
] as const;
export const schemaObjectStatusOptions = [
  "defined",
  "in_sync",
  "drift_detected",
  "pending_migration",
  "unknown"
] as const;
export const driftTypeOptions = [
  "missing_actual",
  "untracked_actual",
  "definition_changed",
  "pending_or_failed_control"
] as const;
export const snapshotDiffTypeOptions = ["added", "changed", "removed"] as const;

const rfc3339TimestampSchema = z
  .string()
  .datetime({ offset: true })
  .transform((value) => value as Rfc3339Timestamp);
const envSchema = z.enum(envOptions);
const runStatusSchema = z.enum(runStatusOptions);
const patchStatusSchema = z.enum(patchStatusOptions);
const scriptStatusSchema = z.enum(scriptStatusOptions);
const severitySchema = z.enum(severityOptions);
const validationStatusSchema = z.enum(validationStatusOptions);
const objectTypeSchema = z.enum(objectTypeOptions);
const dbKindSchema = z.enum(["postgres", "oracle"]);
const schemaObjectStatusSchema = z.enum(schemaObjectStatusOptions);
const driftTypeSchema = z.enum(driftTypeOptions);
const snapshotDiffTypeSchema = z.enum(snapshotDiffTypeOptions);
const nullableOptionalStringSchema = z.string().nullish();

export interface Target {
  id: string;
  label: string;
  app_name: string;
  env: Env;
  jdbc_url: string;
  created_at: Rfc3339Timestamp;
  repo_url: string;
  repo_branch: string;
  repo_sql_path: string;
  last_synced_commit?: string | null;
  last_synced_at?: Rfc3339Timestamp | null;
}

export interface ScriptError {
  db_code: string;
  message: string;
  hint?: string;
  context?: string;
  line?: number;
}

export interface Script {
  id: string;
  patch_id: string;
  order: number;
  filename: string;
  checksum: string;
  status: ScriptStatus;
  error?: ScriptError;
  duration_ms?: number;
}

export interface Patch {
  id: string;
  target_id: string;
  version: string;
  label: string;
  scripts: Script[];
  status: PatchStatus;
  applied_at?: Rfc3339Timestamp;
  source_snapshot_id?: string | null;
}

export interface Run {
  id: string;
  target_id: string;
  patch_id: string;
  status: RunStatus;
  scripts: ScriptRun[];
  started_at: Rfc3339Timestamp;
  ended_at?: Rfc3339Timestamp;
  triggered_by: string;
}

export interface ScriptRun {
  script_id: string;
  filename: string;
  order: number;
  status: ScriptStatus;
  error?: ScriptError;
  duration_ms?: number;
}

export interface ValidationResult {
  run_id: string;
  target_id: string;
  checked_at: Rfc3339Timestamp;
  invalid: InvalidObject[];
  status: ValidationStatus;
}

export interface SqlFilesValidationResult {
  target_id: string;
  db_kind: DbKind;
  checked_at: Rfc3339Timestamp;
  file_count: number;
  invalid: InvalidObject[];
  status: ValidationStatus;
}

export interface InvalidObject {
  object_type: ObjectType;
  schema: string;
  name: string;
  error: string;
  severity: Severity;
}

export interface SchemaCatalogObject {
  schema: string;
  name: string;
  object_type: ObjectType;
  status: SchemaObjectStatus;
  source_file?: string | null;
  checksum?: string | null;
  apply_status?: string | null;
  actual_ddl?: string | null;
  expected_ddl?: string | null;
  last_checked: Rfc3339Timestamp;
}

export interface SchemaCatalogResponse {
  target_id: string;
  db_kind: DbKind;
  supported: boolean;
  checked_at: Rfc3339Timestamp;
  objects: SchemaCatalogObject[];
  warnings: string[];
}

export interface DriftItem {
  schema: string;
  name: string;
  object_type: ObjectType;
  drift_type: DriftType;
  expected: string;
  actual: string;
  source_file?: string | null;
  checksum?: string | null;
  apply_status?: string | null;
  detected_at: Rfc3339Timestamp;
}

export interface SchemaControlSummary {
  total_count: number;
  applied_count: number;
  skipped_count: number;
  pending_count: number;
  failed_count: number;
  ready: boolean;
  failed_objects: string[];
  last_applied_at?: Rfc3339Timestamp | null;
  last_updated_at?: Rfc3339Timestamp | null;
}

export interface SchemaControlObject {
  kind: string;
  object_name: string;
  source_file: string;
  apply_status: string;
  checksum: string;
  applied_at?: Rfc3339Timestamp | null;
  updated_at?: Rfc3339Timestamp | null;
}

export interface DriftResponse {
  target_id: string;
  db_kind: DbKind;
  supported: boolean;
  checked_at: Rfc3339Timestamp;
  control_summary?: SchemaControlSummary | null;
  control_objects: SchemaControlObject[];
  items: DriftItem[];
  warnings: string[];
}

export interface SnapshotFile {
  path: string;
  folder?: string;
  filename?: string;
  sha256: string;
  size_bytes?: number;
  uploaded_at?: Rfc3339Timestamp;
}

export interface Snapshot {
  id: string;
  target_id: string;
  label: string;
  created_at: Rfc3339Timestamp;
  created_by: string;
  file_count: number;
  files?: SnapshotFile[];
}

export interface SnapshotDiffItem {
  path: string;
  diff_type: SnapshotDiffType;
  before_sha256?: string | null;
  after_sha256?: string | null;
}

export interface SnapshotDiff {
  snapshot_id: string;
  other_snapshot_id: string;
  generated_at?: Rfc3339Timestamp | null;
  items: SnapshotDiffItem[];
}

export interface AuditEvent {
  id: string;
  actor: string;
  role?: string | null;
  action: string;
  entity_type: string;
  entity_id: string;
  at: Rfc3339Timestamp;
  target_id?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface ConnectionTestResult {
  ok: boolean;
  latency_ms?: number;
  error?: string;
}

export interface RunStreamState {
  scriptEvents: Map<string, ScriptRun>;
  logLines: string[];
  runStatus: RunStatus;
}

export const scriptErrorSchema = z.object({
  db_code: z.string().min(1, "DB code is required"),
  message: z.string().min(1, "Message is required"),
  hint: z.string().optional(),
  context: z.string().optional(),
  line: z.coerce.number().int().positive().optional()
});

export const targetSchema = z.object({
  id: z.string().min(1),
  label: z.string(),
  app_name: z.string(),
  env: envSchema,
  jdbc_url: z.string(),
  created_at: rfc3339TimestampSchema,
  repo_url: z.string(),
  repo_branch: z.string().default("main"),
  repo_sql_path: z.string().default("sql"),
  last_synced_commit: nullableOptionalStringSchema,
  last_synced_at: rfc3339TimestampSchema.nullish()
});

export const connectionTestResultSchema = z.object({
  ok: z.boolean(),
  latency_ms: z.number().optional(),
  error: z.string().optional()
});

export const scriptSchema = z.object({
  id: z.string().min(1),
  patch_id: z.string().min(1),
  order: z.number().int(),
  filename: z.string(),
  checksum: z.string(),
  status: scriptStatusSchema,
  error: scriptErrorSchema.optional(),
  duration_ms: z.number().optional()
});

export const patchSchema = z.object({
  id: z.string().min(1),
  target_id: z.string().min(1),
  version: z.string(),
  label: z.string(),
  scripts: z.array(scriptSchema),
  status: patchStatusSchema,
  applied_at: rfc3339TimestampSchema.optional(),
  source_snapshot_id: nullableOptionalStringSchema
});

export const scriptRunSchema = z.object({
  script_id: z.string().min(1),
  filename: z.string(),
  order: z.number().int(),
  status: scriptStatusSchema,
  error: scriptErrorSchema.optional(),
  duration_ms: z.number().optional()
});

export const runSchema = z.object({
  id: z.string().min(1),
  target_id: z.string().min(1),
  patch_id: z.string().min(1),
  status: runStatusSchema,
  scripts: z.array(scriptRunSchema),
  started_at: rfc3339TimestampSchema,
  ended_at: rfc3339TimestampSchema.optional(),
  triggered_by: z.string()
});

export const invalidObjectSchema = z.object({
  object_type: objectTypeSchema,
  schema: z.string(),
  name: z.string(),
  error: z.string(),
  severity: severitySchema
});

export const validationResultSchema = z.object({
  run_id: z.string().min(1),
  target_id: z.string().min(1),
  checked_at: rfc3339TimestampSchema,
  invalid: z.array(invalidObjectSchema),
  status: validationStatusSchema
});

export const sqlFilesValidationResultSchema = z.object({
  target_id: z.string().min(1),
  db_kind: dbKindSchema,
  checked_at: rfc3339TimestampSchema,
  file_count: z.number().int().nonnegative(),
  invalid: z.array(invalidObjectSchema),
  status: validationStatusSchema
});

export const schemaCatalogObjectSchema = z.object({
  schema: z.string(),
  name: z.string(),
  object_type: objectTypeSchema.catch("other"),
  status: schemaObjectStatusSchema.catch("unknown"),
  source_file: nullableOptionalStringSchema,
  checksum: nullableOptionalStringSchema,
  apply_status: nullableOptionalStringSchema,
  actual_ddl: nullableOptionalStringSchema,
  expected_ddl: nullableOptionalStringSchema,
  last_checked: rfc3339TimestampSchema
});

export const schemaCatalogResponseSchema = z.object({
  target_id: z.string().min(1),
  db_kind: dbKindSchema,
  supported: z.boolean(),
  checked_at: rfc3339TimestampSchema,
  objects: z.array(schemaCatalogObjectSchema),
  warnings: z.array(z.string())
});

export const driftItemSchema = z.object({
  schema: z.string(),
  name: z.string(),
  object_type: objectTypeSchema.catch("other"),
  drift_type: driftTypeSchema,
  expected: z.string(),
  actual: z.string(),
  source_file: nullableOptionalStringSchema,
  checksum: nullableOptionalStringSchema,
  apply_status: nullableOptionalStringSchema,
  detected_at: rfc3339TimestampSchema
});

export const schemaControlSummarySchema = z.object({
  total_count: z.number().int().nonnegative(),
  applied_count: z.number().int().nonnegative(),
  skipped_count: z.number().int().nonnegative(),
  pending_count: z.number().int().nonnegative(),
  failed_count: z.number().int().nonnegative(),
  ready: z.boolean(),
  failed_objects: z.array(z.string()),
  last_applied_at: rfc3339TimestampSchema.nullish(),
  last_updated_at: rfc3339TimestampSchema.nullish()
});

export const schemaControlObjectSchema = z.object({
  kind: z.string(),
  object_name: z.string(),
  source_file: z.string(),
  apply_status: z.string(),
  checksum: z.string(),
  applied_at: rfc3339TimestampSchema.nullish(),
  updated_at: rfc3339TimestampSchema.nullish()
});

export const driftResponseSchema = z.object({
  target_id: z.string().min(1),
  db_kind: dbKindSchema,
  supported: z.boolean(),
  checked_at: rfc3339TimestampSchema,
  control_summary: schemaControlSummarySchema.nullish(),
  control_objects: z.array(schemaControlObjectSchema).default([]),
  items: z.array(driftItemSchema),
  warnings: z.array(z.string())
});

export const snapshotFileSchema = z.object({
  path: z.string().min(1),
  folder: z.string().optional(),
  filename: z.string().optional(),
  sha256: z.string().min(1),
  size_bytes: z.number().int().nonnegative().optional(),
  uploaded_at: rfc3339TimestampSchema.optional()
});

export const snapshotSchema = z
  .object({
    id: z.string().min(1),
    target_id: z.string().min(1),
    label: z.string().default("Snapshot"),
    created_at: rfc3339TimestampSchema,
    created_by: z.string().default("unknown"),
    file_count: z.number().int().nonnegative().optional(),
    files: z.array(snapshotFileSchema).optional()
  })
  .transform((snapshot) => ({
    ...snapshot,
    file_count: snapshot.file_count ?? snapshot.files?.length ?? 0
  }));

export const snapshotDiffItemSchema = z.object({
  path: z.string().min(1),
  diff_type: snapshotDiffTypeSchema,
  before_sha256: nullableOptionalStringSchema,
  after_sha256: nullableOptionalStringSchema
});

export const snapshotDiffSchema = z.object({
  snapshot_id: z.string().min(1),
  other_snapshot_id: z.string().min(1),
  generated_at: rfc3339TimestampSchema.nullish(),
  items: z.array(snapshotDiffItemSchema)
});

export const auditEventSchema = z.object({
  id: z.string().min(1),
  actor: z.string().default("unknown"),
  role: z.string().nullish(),
  action: z.string().min(1),
  entity_type: z.string().min(1),
  entity_id: z.string().min(1),
  at: rfc3339TimestampSchema,
  target_id: nullableOptionalStringSchema,
  metadata: z.record(z.string(), z.unknown()).nullish()
});

export const parseTarget = (value: unknown): Target => targetSchema.parse(value) as Target;
export const parseTargetList = (value: unknown): Target[] => {
  const parsed = z.union([z.array(targetSchema), z.object({ targets: z.array(targetSchema) })]).parse(value);
  return (Array.isArray(parsed) ? parsed : parsed.targets) as Target[];
};
export const parseConnectionTestResult = (value: unknown): ConnectionTestResult =>
  connectionTestResultSchema.parse(value) as ConnectionTestResult;
export const parsePatch = (value: unknown): Patch => patchSchema.parse(value) as Patch;
export const parsePatchList = (value: unknown): Patch[] => {
  const parsed = z.union([z.array(patchSchema), z.object({ patches: z.array(patchSchema) })]).parse(value);
  return (Array.isArray(parsed) ? parsed : parsed.patches) as Patch[];
};
export const parseRun = (value: unknown): Run => runSchema.parse(value) as Run;
export const parseRunList = (value: unknown): Run[] => {
  const parsed = z.union([z.array(runSchema), z.object({ runs: z.array(runSchema) })]).parse(value);
  return (Array.isArray(parsed) ? parsed : parsed.runs) as Run[];
};
export const parseValidationResult = (value: unknown): ValidationResult =>
  validationResultSchema.parse(value) as ValidationResult;
export const parseSqlFilesValidationResult = (value: unknown): SqlFilesValidationResult =>
  sqlFilesValidationResultSchema.parse(value) as SqlFilesValidationResult;
export const parseSchemaCatalogResponse = (value: unknown): SchemaCatalogResponse =>
  schemaCatalogResponseSchema.parse(value) as SchemaCatalogResponse;
export const parseDriftResponse = (value: unknown): DriftResponse => driftResponseSchema.parse(value) as DriftResponse;
export const parseSnapshot = (value: unknown): Snapshot => snapshotSchema.parse(value) as Snapshot;
export const parseSnapshotList = (value: unknown): Snapshot[] => {
  const parsed = z.union([z.array(snapshotSchema), z.object({ snapshots: z.array(snapshotSchema) })]).parse(value);
  return (Array.isArray(parsed) ? parsed : parsed.snapshots) as Snapshot[];
};
export const parseSnapshotDiff = (value: unknown): SnapshotDiff => snapshotDiffSchema.parse(value) as SnapshotDiff;
export const parseAuditEvent = (value: unknown): AuditEvent => auditEventSchema.parse(value) as AuditEvent;
export const parseAuditEventList = (value: unknown): AuditEvent[] => {
  const parsed = z.union([z.array(auditEventSchema), z.object({ events: z.array(auditEventSchema) })]).parse(value);
  return (Array.isArray(parsed) ? parsed : parsed.events) as AuditEvent[];
};

const postgresJdbcPrefix = "jdbc:postgresql:";
const oracleJdbcPrefix = "jdbc:oracle:thin:";
const postgresUrlPrefix = "postgres://";
const postgresqlUrlPrefix = "postgresql://";
const supportedDatabaseUrlMessage =
  "Use postgres://user:password@host:5432/database, jdbc:postgresql://host:5432/database?user=username, or jdbc:oracle:thin:@...";

export const targetFormSchema = z.object({
  label: z.string().trim().min(1, "Label is required"),
  app_name: z.string().trim().min(1, "Application is required"),
  env: z.enum(envOptions),
  jdbc_url: z
    .string()
    .trim()
    .min(1, "Database URL is required")
    .superRefine((value, ctx) => {
      if (value.startsWith(postgresUrlPrefix) || value.startsWith(postgresqlUrlPrefix)) {
        return;
      }
      if (value.startsWith("jdbc:postgres://")) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: "Postgres JDBC URLs must start with jdbc:postgresql://" });
        return;
      }
      if (!value.startsWith(postgresJdbcPrefix) && !value.startsWith(oracleJdbcPrefix)) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: supportedDatabaseUrlMessage });
      }
    }),
  password: z.string().optional(),
  repo_url: z
    .string()
    .trim()
    .min(1, "Repository URL is required")
    .url("Repository URL must be a valid URL")
    .refine((value) => value.startsWith("https://"), "Repository URL must start with https://")
    .refine((value) => !/^https:\/\/[^/?#\s]*@/iu.test(value), "Repository URL must not include credentials"),
  repo_branch: z.string().trim().min(1, "Branch is required"),
  repo_sql_path: z
    .string()
    .trim()
    .min(1, "SQL path is required")
    .refine(
      (value) => !value.startsWith("/") && !value.includes("..") && !value.includes("\\"),
      "SQL path must stay inside the repository"
    )
});

export type TargetFormValues = z.infer<typeof targetFormSchema>;

export type TargetPayload = Omit<TargetFormValues, "password"> & {
  password?: string | undefined;
};

export const normalizeTargetPayload = (values: TargetFormValues): TargetPayload => {
  const password = values.password?.trim();
  return {
    label: values.label.trim(),
    app_name: values.app_name.trim(),
    env: values.env,
    jdbc_url: values.jdbc_url.trim(),
    repo_url: values.repo_url.trim(),
    repo_branch: values.repo_branch.trim() || "main",
    repo_sql_path: values.repo_sql_path.trim() || "sql",
    ...(password ? { password } : {})
  };
};

export interface TriggerRunPayload {
  patch_id: string;
  target_id: string;
}

export interface DriftRunPayload {
  target_id: string;
  source_files?: string[];
}

export interface CreatePatchFromSqlFilesPayload {
  target_id: string;
  source_files: string[];
}

export interface UploadPatchPayload {
  target_id: string;
  files: File[];
}

export interface ValidateSqlFilesPayload {
  target_id: string;
}

export interface ValidateSqlDirectoryPayload {
  sql_dir: string;
  db_kind: DbKind;
  customer?: string;
}

export interface CreateSnapshotPayload {
  target_id: string;
  label?: string;
}

export interface RollbackToSnapshotPayload {
  snapshot_id: string;
  target_id: string;
  source_type?: "patch" | "run";
  source_id?: string;
}
