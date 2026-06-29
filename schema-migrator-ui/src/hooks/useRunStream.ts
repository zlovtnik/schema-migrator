import { useEffect, useMemo, useRef, useState } from "react";
import { buildApiUrl, ensureAuthToken, getAuthToken } from "../api/client";
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

const parseEventData = <T>(data: string): T => JSON.parse(data) as T;

const parseSseBlock = (block: string): { eventName: string; data: string } | undefined => {
  const dataLines: string[] = [];
  let eventName = "message";

  block.split(/\r?\n/).forEach((line) => {
    if (!line || line.startsWith(":")) {
      return;
    }

    const separator = line.indexOf(":");
    const field = separator === -1 ? line : line.slice(0, separator);
    const rawValue = separator === -1 ? "" : line.slice(separator + 1);
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;

    if (field === "event") {
      eventName = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
  });

  return dataLines.length > 0 ? { eventName, data: dataLines.join("\n") } : undefined;
};

export const useRunStream = (runId?: string, initialRun?: Run, options: UseRunStreamOptions = {}) => {
  const { enabled = true, onRunComplete, onRunFailed } = options;
  const [state, setState] = useState<RunStreamState>(() => toInitialState(initialRun));
  const sourceRef = useRef<AbortController | null>(null);
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
      sourceRef.current?.abort();
      sourceRef.current = null;
    };

    const handleStreamEvent = (eventName: string, dataText: string) => {
      if (eventName === "script:start") {
        const data = parseEventData<ScriptStartEvent>(dataText);
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
        return;
      }

      if (eventName === "script:complete") {
        const data = parseEventData<ScriptCompleteEvent>(dataText);
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
        return;
      }

      if (eventName === "script:error") {
        const data = parseEventData<ScriptErrorEvent>(dataText);
        setState((previous) => {
          const current = previous.scriptEvents.get(data.script_id);
          const error: ScriptError = {
            db_code: data.db_code,
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
        return;
      }

      if (eventName === "run:complete") {
        const data = parseEventData<RunCompleteEvent>(dataText);
        setState((previous) => ({ ...previous, runStatus: "completed" }));
        callbacksRef.current.onRunComplete?.(data);
        attempts = 0;
        closed = true;
        closeSource();
        return;
      }

      if (eventName === "run:failed") {
        const data = parseEventData<RunFailedEvent>(dataText);
        setState((previous) => ({ ...previous, runStatus: data.reason === "aborted" ? "aborted" : "failed" }));
        callbacksRef.current.onRunFailed?.(data);
        attempts = 0;
        closed = true;
        closeSource();
        return;
      }

      if (eventName === "log") {
        const data = parseEventData<LogEvent>(dataText);
        setState((previous) => ({
          ...previous,
          logLines: [...previous.logLines, `${data.ts} ${data.level.toUpperCase()} ${data.message}`].slice(-2000)
        }));
      }
    };

    const connect = async () => {
      closeSource();
      if (closed) {
        return;
      }

      const controller = new AbortController();
      const headers = new Headers();
      await ensureAuthToken();
      const token = getAuthToken();
      if (token) {
        headers.set("Authorization", `Bearer ${token}`);
      }

      sourceRef.current = controller;
      try {
        const response = await fetch(buildApiUrl(`/runs/${runId}/stream`), {
          credentials: "same-origin",
          headers,
          signal: controller.signal
        });
        if (!response.ok || !response.body) {
          throw new Error(`run stream failed with ${response.status}`);
        }

        const reader = response.body.getReader();
        attempts = 0;
        const decoder = new TextDecoder();
        let buffer = "";
        let boundary = /\r?\n\r?\n/.exec(buffer);

        while (!closed) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          boundary = /\r?\n\r?\n/.exec(buffer);
          while (boundary) {
            const block = buffer.slice(0, boundary.index);
            buffer = buffer.slice(boundary.index + boundary[0].length);
            const parsed = parseSseBlock(block);
            if (parsed) {
              handleStreamEvent(parsed.eventName, parsed.data);
            }
            boundary = /\r?\n\r?\n/.exec(buffer);
          }
        }

        const remaining = decoder.decode();
        if (remaining) {
          buffer += remaining;
        }
        if (buffer.trim()) {
          const parsed = parseSseBlock(buffer);
          if (parsed) {
            handleStreamEvent(parsed.eventName, parsed.data);
          }
        }

        if (!closed) {
          throw new Error("run stream ended before a terminal event");
        }
      } catch (error) {
        if (closed || controller.signal.aborted || attempts >= 3) {
          return;
        }
        attempts += 1;
        retryTimer = window.setTimeout(() => {
          void connect();
        }, Math.min(1000 * 2 ** attempts, 8000));
      }
    };

    void connect();

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
