import { WarningIcon } from "@phosphor-icons/react/dist/csr/Warning";
import { Link } from "react-router-dom";
import type { Run } from "../types";
import { Icon } from "./ui/Icon";

interface ErrorGateBannerProps {
  failedRun?: Run | undefined;
}

export const ErrorGateBanner = ({ failedRun }: ErrorGateBannerProps) => {
  if (!failedRun) {
    return null;
  }

  return (
    <div className="error-gate" role="alert">
      <Icon source={WarningIcon} size={20} weight="bold" />
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
