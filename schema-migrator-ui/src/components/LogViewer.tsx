import { useEffect, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";

interface LogViewerProps {
  lines: string[];
}

export const LogViewer = ({ lines }: LogViewerProps) => {
  const parentRef = useRef<HTMLDivElement | null>(null);
  const [scrollPaused, setScrollPaused] = useState(false);
  const [hoverSuspended, setHoverSuspended] = useState(false);
  const autoScroll = !scrollPaused && !hoverSuspended;

  const rowVirtualizer = useVirtualizer({
    count: lines.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 22,
    overscan: 12
  });

  useEffect(() => {
    if (!autoScroll || lines.length === 0) {
      return;
    }
    rowVirtualizer.scrollToIndex(lines.length - 1, { align: "end" });
  }, [autoScroll, lines.length, rowVirtualizer]);

  return (
    <div className="log-viewer">
      <div className="log-viewer__toolbar">
        <span>{lines.length} log lines</span>
        <button className="button button--ghost button--small" type="button" onClick={() => setScrollPaused((value) => !value)}>
          {scrollPaused ? "Resume scroll" : "Pause scroll"}
        </button>
      </div>
      <div
        className="log-viewer__body"
        ref={parentRef}
        onMouseEnter={() => setHoverSuspended(true)}
        onMouseLeave={() => setHoverSuspended(false)}
      >
        <div style={{ height: `${rowVirtualizer.getTotalSize()}px`, position: "relative" }}>
          {rowVirtualizer.getVirtualItems().map((virtualRow) => (
            <div
              className="log-viewer__line"
              key={virtualRow.key}
              style={{
                height: `${virtualRow.size}px`,
                transform: `translateY(${virtualRow.start}px)`
              }}
            >
              {lines[virtualRow.index]}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
