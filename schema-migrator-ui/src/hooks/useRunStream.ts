import { useEffect, useMemo, useRef, useState } from "react";
import { buildApiUrl } from "../api/client";
import type { Run, RunStatus, RunStreamState, ScriptError, ScriptRun } from "../types";

type ScriptStartEvent = {
  script_id: string;
  filename: string;
  order: number;
  total: number;
};

type ScriptCompleteEvent = {
  script_id: string;
  duration_ms: number;
};

type ScriptErrorEvent = ScriptError & {
  script_id: string;
};

type RunCompleteEvent = {
  run_id: string;
  duration_ms: number;
  validation_triggered: boolean;
};

type RunFailedEvent = {
  run_id: string;
  failed_script_id: string;
  reason: string;
};

type LogEvent = {
  level: string;
  message: string;
  ts: string;
};

interface UseRunStreamOptions {
  enabled?: boolean;
  onRunComplete?: (event: RunCompleteEvent) => void;
  onRunFailed?: (event: RunFailedEvent) => void;
}

const terminalStatuses: RunStatus[] = ["completed", "failed", "aborted"];

const toInitialState = (run?: Run): RunStreamState => ({
  scriptEvents: new Map(run?.scripts.map((script) => [script.script_id, script]) ?? []),
  logLines: [],
  runStatus: run?.status ?? "pending"
});

const parseEventData = <T>(event: MessageEvent<string>): T => JSON.parse(event.data) as T;

export const useRunStream = (runId?: string, initialRun?: Run, options: UseRunStreamOptions = {}) => {
  const { enabled = true, onRunComplete, onRunFailed } = options;
  const [state, setState] = useState<RunStreamState>(() => toInitialState(initialRun));
  const sourceRef = useRef<EventSource | null>(null);
  const callbacksRef = useRef({ onRunComplete, onRunFailed });
  const previousRunIdRef = useRef<string | undefined>(runId);

  callbacksRef.current = { onRunComplete, onRunFailed };

  useEffect(() => {
    if (previousRunIdRef.current !== runId) {
      previousRunIdRef.current = runId;
      setState(toInitialState(initialRun?.id === runId ? initialRun : undefined));
    }
  }, [initialRun, runId]);

  useEffect(() => {
    if (!initialRun || initialRun.id !== runId) {
      return;
    }

    setState((previous) => {
      const scriptEvents = new Map(previous.scriptEvents);
      initialRun.scripts.forEach((script) => {
        if (!scriptEvents.has(script.script_id)) {
          scriptEvents.set(script.script_id, script);
        }
      });

      return {
        ...previous,
        scriptEvents,
        runStatus: initialRun.status
      };
    });
  }, [initialRun, runId]);

  useEffect(() => {
    if (!runId || !enabled || terminalStatuses.includes(initialRun?.status ?? "pending")) {
      return undefined;
    }

    let closed = false;
    let retryTimer: number | undefined;
    let attempts = 0;

    const closeSource = () => {
      sourceRef.current?.close();
      sourceRef.current = null;
    };

    const connect = () => {
      closeSource();
      if (closed) {
        return;
      }

      const source = new EventSource(buildApiUrl(`/runs/${runId}/stream`), { withCredentials: true });
      sourceRef.current = source;

      source.addEventListener("open", () => {
        attempts = 0;
      });

      source.addEventListener("script:start", (event) => {
        const data = parseEventData<ScriptStartEvent>(event as MessageEvent<string>);
        setState((previous) => {
          const nextScript: ScriptRun = {
            script_id: data.script_id,
            filename: data.filename,
            order: data.order,
            status: "running"
          };
          const scriptEvents = new Map(previous.scriptEvents).set(data.script_id, nextScript);
          return { ...previous, scriptEvents, runStatus: "running" };
        });
      });

      source.addEventListener("script:complete", (event) => {
        const data = parseEventData<ScriptCompleteEvent>(event as MessageEvent<string>);
        setState((previous) => {
          const current = previous.scriptEvents.get(data.script_id);
          if (!current) {
            return previous;
          }
          const scriptEvents = new Map(previous.scriptEvents).set(data.script_id, {
            ...current,
            status: "completed",
            duration_ms: data.duration_ms
          });
          return { ...previous, scriptEvents };
        });
      });

      source.addEventListener("script:error", (event) => {
        const data = parseEventData<ScriptErrorEvent>(event as MessageEvent<string>);
        setState((previous) => {
          const current = previous.scriptEvents.get(data.script_id);
          const error: ScriptError = {
            pg_code: data.pg_code,
            message: data.message,
            ...(data.hint !== undefined ? { hint: data.hint } : {}),
            ...(data.context !== undefined ? { context: data.context } : {}),
            ...(data.line !== undefined ? { line: data.line } : {})
          };
          const scriptEvents = new Map(previous.scriptEvents).set(data.script_id, {
            script_id: data.script_id,
            filename: current?.filename ?? data.script_id,
            order: current?.order ?? previous.scriptEvents.size + 1,
            status: "failed",
            error
          });
          return { ...previous, scriptEvents, runStatus: "failed" };
        });
      });

      source.addEventListener("run:complete", (event) => {
        const data = parseEventData<RunCompleteEvent>(event as MessageEvent<string>);
        setState((previous) => ({ ...previous, runStatus: "completed" }));
        callbacksRef.current.onRunComplete?.(data);
        closeSource();
      });

      source.addEventListener("run:failed", (event) => {
        const data = parseEventData<RunFailedEvent>(event as MessageEvent<string>);
        setState((previous) => ({ ...previous, runStatus: "failed" }));
        callbacksRef.current.onRunFailed?.(data);
        closeSource();
      });

      source.addEventListener("log", (event) => {
        const data = parseEventData<LogEvent>(event as MessageEvent<string>);
        setState((previous) => ({
          ...previous,
          logLines: [...previous.logLines, `${data.ts} ${data.level.toUpperCase()} ${data.message}`].slice(-2000)
        }));
      });

      source.onerror = () => {
        closeSource();
        if (closed || attempts >= 3) {
          return;
        }
        attempts += 1;
        retryTimer = window.setTimeout(connect, Math.min(1000 * 2 ** attempts, 8000));
      };
    };

    connect();

    return () => {
      closed = true;
      if (retryTimer) {
        window.clearTimeout(retryTimer);
      }
      closeSource();
    };
  }, [enabled, initialRun?.status, runId]);

  const orderedScripts = useMemo(
    () => Array.from(state.scriptEvents.values()).sort((a, b) => a.order - b.order),
    [state.scriptEvents]
  );

  return {
    ...state,
    orderedScripts
  };
};
