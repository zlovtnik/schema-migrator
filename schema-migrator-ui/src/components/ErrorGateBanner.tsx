import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { Link } from "react-router-dom";
import type { Run } from "../types";
import { Icon } from "./ui/Icon";

interface ErrorGateBannerProps {
  failedRun?: Run | undefined;
  canResolve?: boolean;
  resolving?: boolean;
  resolveTitle?: string | undefined;
  onResolve?: (runId: string) => void;
}

export const ErrorGateBanner = ({
  failedRun,
  canResolve = true,
  resolving = false,
  resolveTitle,
  onResolve
}: ErrorGateBannerProps) => {
  if (!failedRun) {
    return null;
  }

  return (
    <div className="error-gate" role="alert">
      <Icon source={WarningIcon} size={20} weight="bold" />
      <div>
        <strong>Apply is blocked by a failed run.</strong>
        <span>Resolve run {failedRun.id} before starting another apply.</span>
      </div>
      <Link className="button button--danger button--small" to={`/runs/${failedRun.id}`}>
        View run
      </Link>
      {onResolve ? (
        <button
          className="button button--secondary button--small"
          type="button"
          disabled={!canResolve || resolving}
          title={resolveTitle}
          onClick={() => onResolve(failedRun.id)}
        >
          {resolving ? "Resolving" : "Resolve"}
        </button>
      ) : null}
    </div>
  );
};
