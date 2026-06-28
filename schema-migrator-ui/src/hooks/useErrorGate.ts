import { useMemo } from "react";
import { useRuns } from "./useRuns";

export const useErrorGate = () => {
  const runsQuery = useRuns();

  const latestRun = useMemo(
    () =>
      runsQuery.data
        ?.slice()
        .sort((a, b) => Date.parse(b.started_at) - Date.parse(a.started_at))[0],
    [runsQuery.data]
  );

  const failedRun = latestRun?.status === "failed" ? latestRun : undefined;

  return {
    ...runsQuery,
    failedRun,
    isGateBlocked: Boolean(failedRun)
  };
};
