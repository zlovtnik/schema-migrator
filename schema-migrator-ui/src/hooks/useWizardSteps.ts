import { useCallback, useState } from "react";

export const useWizardSteps = <T>(steps: readonly T[], initialIndex = 0) => {
  const lastIndex = Math.max(0, steps.length - 1);
  const clamp = useCallback((index: number) => Math.min(Math.max(0, index), lastIndex), [lastIndex]);
  const [currentIndex, setCurrentIndex] = useState(() => clamp(initialIndex));

  const goTo = useCallback(
    (index: number, allowed = true) => {
      setCurrentIndex((current) => (index > current && !allowed ? current : clamp(index)));
    },
    [clamp]
  );
  const next = useCallback(
    (allowed = true) => {
      setCurrentIndex((current) => (!allowed ? current : clamp(current + 1)));
    },
    [clamp]
  );
  const back = useCallback(() => setCurrentIndex((current) => clamp(current - 1)), [clamp]);
  const reset = useCallback(() => setCurrentIndex(clamp(initialIndex)), [clamp, initialIndex]);

  return {
    currentIndex,
    currentStep: steps[currentIndex],
    goTo,
    next,
    back,
    reset,
    isFirst: currentIndex === 0,
    isLast: currentIndex === lastIndex
  } as const;
};
