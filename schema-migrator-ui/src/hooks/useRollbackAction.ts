import { useState } from "react";
import type { RollbackToSnapshotPayload } from "../types";
import { useRollbackToSnapshot } from "./useSnapshots";

export const useRollbackAction = () => {
  const rollbackToSnapshot = useRollbackToSnapshot();
  const [confirmOpen, setConfirmOpen] = useState(false);

  return {
    confirmOpen,
    isPending: rollbackToSnapshot.isPending,
    openConfirm: () => setConfirmOpen(true),
    closeConfirm: () => setConfirmOpen(false),
    confirm: (canMutate: boolean, payload: RollbackToSnapshotPayload | undefined) => {
      if (!canMutate || !payload) {
        return;
      }
      rollbackToSnapshot.mutate(payload);
    }
  };
};
