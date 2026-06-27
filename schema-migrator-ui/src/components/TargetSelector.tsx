import { useEffect } from "react";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { useSearchParams } from "react-router-dom";
import { useTargets } from "../hooks/useTargets";
import { StatusBadge } from "./StatusBadge";
import { Icon } from "./ui/Icon";

interface TargetSelectorProps {
  compact?: boolean;
  paramName?: string;
}

export const TargetSelector = ({ compact = false, paramName = "target" }: TargetSelectorProps) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: targets = [], isLoading } = useTargets();
  const selectedId = searchParams.get(paramName) || "";
  const selectedTarget = targets.find((target) => target.id === selectedId);

  useEffect(() => {
    if (isLoading || !selectedId || selectedTarget) {
      return;
    }
    const next = new URLSearchParams(searchParams);
    next.delete(paramName);
    setSearchParams(next, { replace: true });
  }, [isLoading, paramName, searchParams, selectedId, selectedTarget, setSearchParams]);

  const updateTarget = (value: string) => {
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set(paramName, value);
    } else {
      next.delete(paramName);
    }
    setSearchParams(next, { replace: false });
  };

  return (
    <label className={compact ? "target-selector target-selector--compact" : "target-selector"}>
      <span>
        <Icon source={DatabaseIcon} size={16} />
        Target
      </span>
      <select value={selectedId} onChange={(event) => updateTarget(event.target.value)} disabled={isLoading}>
        <option value="">{isLoading ? "Loading targets" : "Select target"}</option>
        {targets.map((target) => (
          <option value={target.id} key={target.id}>
            {target.label} ({target.env})
          </option>
        ))}
      </select>
      {selectedTarget?.env === "production" ? <StatusBadge status="warning" title="Production target" /> : null}
    </label>
  );
};
