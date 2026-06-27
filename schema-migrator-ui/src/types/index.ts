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
export const objectTypeOptions = ["view", "function", "procedure", "trigger", "index", "other"] as const;

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
  password?: string;
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
