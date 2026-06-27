import { AlertTriangle } from "lucide-react";
import { Link } from "react-router-dom";
import type { Run } from "../types";

interface ErrorGateBannerProps {
  failedRun?: Run;
}

export const ErrorGateBanner = ({ failedRun }: ErrorGateBannerProps) => {
  if (!failedRun) {
    return null;
  }

  return (
    <div className="error-gate" role="alert">
      <AlertTriangle size={18} aria-hidden="true" />
      <div>
        <strong>Apply is blocked by a failed run.</strong>
        <span>Resolve run {failedRun.id} before applying more patches.</span>
      </div>
      <Link className="button button--danger button--small" to={`/runs/${failedRun.id}`}>
        View run
      </Link>
    </div>
  );
};
