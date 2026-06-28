import { apiRequest } from "./client";
import { parseValidationResult, type ValidationResult } from "../types";

export const getValidation = (runId: string): Promise<ValidationResult> =>
  apiRequest<unknown>(`/validation/${runId}`).then(parseValidationResult);

export const rerunValidation = (runId: string): Promise<ValidationResult> =>
  apiRequest<unknown>(`/validation/${runId}/rerun`, {
    method: "POST"
  }).then(parseValidationResult);
