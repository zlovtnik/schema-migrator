import { apiRequest } from "./client";
import {
  parseSqlFilesValidationResult,
  parseValidationResult,
  type ValidateSqlDirectoryPayload,
  type SqlFilesValidationResult,
  type ValidateSqlFilesPayload,
  type ValidationResult
} from "../types";

export const getValidation = (runId: string): Promise<ValidationResult> =>
  apiRequest<unknown>(`/validation/${runId}`).then(parseValidationResult);

export const validateSqlFiles = (payload: ValidateSqlFilesPayload): Promise<SqlFilesValidationResult> =>
  apiRequest<unknown>("/validation/sql-files", {
    method: "POST",
    body: payload
  }).then(parseSqlFilesValidationResult);

export const validateSqlDirectory = (payload: ValidateSqlDirectoryPayload): Promise<SqlFilesValidationResult> =>
  apiRequest<unknown>("/validate", {
    method: "POST",
    body: payload
  }).then(parseSqlFilesValidationResult);

export const rerunValidation = (runId: string): Promise<ValidationResult> =>
  apiRequest<unknown>(`/validation/${runId}/rerun`, {
    method: "POST"
  }).then(parseValidationResult);
