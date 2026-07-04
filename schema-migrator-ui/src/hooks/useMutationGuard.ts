import { useCallback } from "react";

export interface MutationGuard {
  disabled: boolean;
  title?: string | undefined;
}

export const useMutationGuard = (canMutate: boolean) =>
  useCallback(
    (viewerTitle: string, disabled = false): MutationGuard => ({
      disabled: !canMutate || disabled,
      title: canMutate ? undefined : viewerTitle
    }),
    [canMutate]
  );
