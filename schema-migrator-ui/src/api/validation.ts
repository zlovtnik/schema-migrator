import { apiRequest } from "./client";
import type { ValidationResult } from "../types";

export const getValidation = (runId: string): Promise<ValidationResult> =>
  apiRequest<ValidationResult>(`/validation/${runId}`);

export const rerunValidation = (runId: string): Promise<ValidationResult> =>
  apiRequest<ValidationResult>(`/validation/${runId}/rerun`, {
    method: "POST"
  });
