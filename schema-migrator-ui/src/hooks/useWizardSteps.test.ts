import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useWizardSteps } from "./useWizardSteps";

describe("useWizardSteps", () => {
  it("guards forward movement and clamps navigation", () => {
    const { result } = renderHook(() => useWizardSteps(["one", "two", "three"] as const));
    act(() => result.current.next(false));
    expect(result.current.currentIndex).toBe(0);
    act(() => result.current.next(true));
    expect(result.current.currentStep).toBe("two");
    act(() => result.current.goTo(2, false));
    expect(result.current.currentIndex).toBe(1);
    act(() => result.current.goTo(2));
    expect(result.current.isLast).toBe(true);
    act(() => result.current.back());
    expect(result.current.currentIndex).toBe(1);
    act(() => result.current.reset());
    expect(result.current.isFirst).toBe(true);
  });

  it("reclamps the current index when steps shrink", () => {
    const { result, rerender } = renderHook(({ steps }: { steps: readonly string[] }) => useWizardSteps(steps), {
      initialProps: { steps: ["one", "two", "three"] }
    });

    act(() => result.current.goTo(2));
    rerender({ steps: ["one", "two"] });

    expect(result.current.currentIndex).toBe(1);
    expect(result.current.currentStep).toBe("two");
  });
});
