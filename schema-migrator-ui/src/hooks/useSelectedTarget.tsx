import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode
} from "react";
import { useSearchParams } from "react-router-dom";

const SELECTED_TARGET_KEY = "schemaMigrator.selectedTargetId";

interface SelectedTargetContextValue {
  selectedTargetId: string;
  setSelectedTargetId: (targetId: string) => void;
}

const SelectedTargetContext = createContext<SelectedTargetContextValue | undefined>(undefined);

export const SelectedTargetProvider = ({ children }: { children: ReactNode }) => {
  const [selectedTargetId, setSelectedTargetIdState] = useState(readStoredTargetId);

  const setSelectedTargetId = useCallback((targetId: string) => {
    const normalized = targetId.trim();
    setSelectedTargetIdState(normalized);
    writeStoredTargetId(normalized);
  }, []);

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key === SELECTED_TARGET_KEY) {
        setSelectedTargetIdState(event.newValue ?? "");
      }
    };

    window.addEventListener("storage", handleStorage);
    return () => window.removeEventListener("storage", handleStorage);
  }, []);

  const value = useMemo(
    () => ({
      selectedTargetId,
      setSelectedTargetId
    }),
    [selectedTargetId, setSelectedTargetId]
  );

  return <SelectedTargetContext.Provider value={value}>{children}</SelectedTargetContext.Provider>;
};

export const useSelectedTarget = () => {
  const context = useContext(SelectedTargetContext);
  if (!context) {
    throw new Error("useSelectedTarget must be used within SelectedTargetProvider");
  }
  return context;
};

export const useSelectedTargetId = (paramName = "target"): string | null => {
  const [searchParams] = useSearchParams();
  const { selectedTargetId, setSelectedTargetId } = useSelectedTarget();
  const urlTargetId = searchParams.get(paramName)?.trim() ?? "";

  useEffect(() => {
    if (urlTargetId && urlTargetId !== selectedTargetId) {
      setSelectedTargetId(urlTargetId);
    }
  }, [selectedTargetId, setSelectedTargetId, urlTargetId]);

  return urlTargetId || selectedTargetId || null;
};

const readStoredTargetId = (): string => {
  try {
    return window.localStorage.getItem(SELECTED_TARGET_KEY)?.trim() ?? "";
  } catch {
    return "";
  }
};

const writeStoredTargetId = (targetId: string) => {
  try {
    if (targetId) {
      window.localStorage.setItem(SELECTED_TARGET_KEY, targetId);
    } else {
      window.localStorage.removeItem(SELECTED_TARGET_KEY);
    }
  } catch {
    // Persisting the target is a convenience; the in-memory context still works.
  }
};
