import { useMemo } from "react";
import { useRuns } from "./useRuns";

export const useErrorGate = () => {
  const runsQuery = useRuns();

  const failedRun = useMemo(
    () =>
      runsQuery.data
        ?.filter((run) => run.status === "failed")
        .sort((a, b) => Date.parse(b.started_at) - Date.parse(a.started_at))[0],
    [runsQuery.data]
  );

  return {
    ...runsQuery,
    failedRun,
    isGateBlocked: Boolean(failedRun)
  };
};
