import { z } from "zod";

export type Rfc3339Timestamp = string & { readonly __brand: "Rfc3339" };
export type SslMode = "disable" | "require" | "verify-ca" | "verify-full";
export type Env = "production" | "staging" | "dev";
export type RunStatus = "pending" | "running" | "completed" | "failed" | "aborted";
export type PatchStatus = "pending" | "applied" | "failed" | "partial";
export type ScriptStatus = "pending" | "running" | "completed" | "failed" | "skipped";
export type Severity = "warning" | "error";
export type ObjectType = "view" | "function" | "procedure" | "trigger" | "index" | "other";
export type ValidationStatus = "clean" | "warnings" | "errors";

export const envOptions = ["production", "staging", "dev"] as const;
export const sslModeOptions = ["disable", "require", "verify-ca", "verify-full"] as const;
export const runStatusOptions = ["pending", "running", "completed", "failed", "aborted"] as const;
export const patchStatusOptions = ["pending", "applied", "failed", "partial"] as const;
export const scriptStatusOptions = ["pending", "running", "completed", "failed", "skipped"] as const;
export const severityOptions = ["warning", "error"] as const;
export const validationStatusOptions = ["clean", "warnings", "errors"] as const;
export const objectTypeOptions = ["view", "function", "procedure", "trigger", "index", "other"] as const;

const rfc3339TimestampSchema = z.string().datetime({ offset: true }).transform((value) => value as Rfc3339Timestamp);
const envSchema = z.enum(envOptions);
const sslModeSchema = z.enum(sslModeOptions);
const runStatusSchema = z.enum(runStatusOptions);
const patchStatusSchema = z.enum(patchStatusOptions);
const scriptStatusSchema = z.enum(scriptStatusOptions);
const severitySchema = z.enum(severityOptions);
const validationStatusSchema = z.enum(validationStatusOptions);
const objectTypeSchema = z.enum(objectTypeOptions);

export interface Target {
  id: string;
  label: string;
  app_name: string;
  env: Env;
  host: string;
  port: number;
  dbname: string;
  user: string;
  schema: string;
  ssl_mode: SslMode;
  created_at: Rfc3339Timestamp;
}

export interface ScriptError {
  pg_code: string;
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

export interface InvalidObject {
  object_type: ObjectType;
  schema: string;
  name: string;
  error: string;
  severity: Severity;
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
  pg_code: z.string().min(1, "PG code is required"),
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
  host: z.string(),
  port: z.number().int(),
  dbname: z.string(),
  user: z.string(),
  schema: z.string(),
  ssl_mode: sslModeSchema,
  created_at: rfc3339TimestampSchema
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
  applied_at: rfc3339TimestampSchema.optional()
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

export const targetFormSchema = z.object({
  label: z.string().trim().min(1, "Label is required"),
  app_name: z.string().trim().min(1, "Application is required"),
  env: z.enum(envOptions),
  host: z.string().trim().min(1, "Host is required"),
  port: z.coerce.number().int().min(1, "Port is required").max(65535, "Port must be 65535 or lower"),
  dbname: z.string().trim().min(1, "Database name is required"),
  user: z.string().trim().min(1, "User is required"),
  password: z.string().optional(),
  schema: z.string().trim().min(1, "Schema is required"),
  ssl_mode: z.enum(sslModeOptions)
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
    host: values.host.trim(),
    port: Number(values.port),
    dbname: values.dbname.trim(),
    user: values.user.trim(),
    schema: values.schema.trim(),
    ssl_mode: values.ssl_mode,
    ...(password ? { password } : {})
  };
};

export interface TriggerRunPayload {
  patch_id: string;
  target_id: string;
}

export interface UploadPatchPayload {
  target_id: string;
  files: File[];
}
