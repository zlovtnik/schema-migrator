import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";
import { scaleTokens } from "./tokens";

const srcDir = join(process.cwd(), "src");
const globalStyles = readFileSync(join(srcDir, "styles.css"), "utf8");
const buttonModuleStyles = readFileSync(join(srcDir, "components/ui/Button.module.css"), "utf8");
const appShell = readFileSync(join(srcDir, "layouts/AppShell.tsx"), "utf8");
const loginPage = readFileSync(join(srcDir, "pages/Login/LoginPage.tsx"), "utf8");
const connectionForm = readFileSync(join(srcDir, "components/ConnectionForm.tsx"), "utf8");

const sourceFiles = (dir: string): string[] =>
  readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    const stats = statSync(path);
    if (stats.isDirectory()) {
      return sourceFiles(path);
    }
    return /\.(ts|tsx|css)$/.test(entry) ? [path] : [];
  });

const allSources = () =>
  sourceFiles(srcDir).map((path) => ({
    label: relative(srcDir, path),
    text: readFileSync(path, "utf8")
  }));

const px = (value: string): number => Number.parseInt(value.replace("px", ""), 10);

describe("WCAG 2.2 AA UI codex", () => {
  it("defines enforceable accessibility tokens", () => {
    expect(px(scaleTokens["--a11y-target-min"])).toBeGreaterThanOrEqual(24);
    expect(px(scaleTokens["--a11y-target-comfort"])).toBeGreaterThanOrEqual(44);
    expect(scaleTokens["--a11y-focus-ring-width"]).toBe("2px");
    expect(scaleTokens["--a11y-focus-ring-offset"]).toBe("2px");
    expect(px(scaleTokens["--a11y-focus-obscured-offset"])).toBeGreaterThanOrEqual(44);
  });

  it("keeps focus rings visible and offsets focused content from overlays", () => {
    expect(globalStyles).toContain("scroll-padding-block-start: var(--a11y-focus-obscured-offset);");
    expect(globalStyles).toContain("scroll-margin-block-start: var(--a11y-focus-obscured-offset);");
    expect(globalStyles).toContain("outline: var(--a11y-focus-ring-width) solid var(--color-accent-primary);");
    expect(globalStyles).toContain("outline-offset: var(--a11y-focus-ring-offset);");
    expect(buttonModuleStyles).toContain("outline: var(--a11y-focus-ring-width) solid var(--color-accent-primary);");
    expect(buttonModuleStyles).toContain("outline-offset: var(--a11y-focus-ring-offset);");
  });

  it("keeps shared controls at or above the WCAG 2.2 AA target floor", () => {
    expect(globalStyles).toContain("min-height: max(38px, var(--a11y-target-min));");
    expect(globalStyles).toContain("min-height: max(32px, var(--a11y-target-min));");
    expect(globalStyles).toContain("width: max(38px, var(--a11y-target-min));");
    expect(globalStyles).toContain("height: max(38px, var(--a11y-target-min));");
    expect(globalStyles).toContain("width: var(--a11y-target-min);");
    expect(buttonModuleStyles).toContain("min-height: max(32px, var(--a11y-target-min));");
    expect(buttonModuleStyles).toContain("min-height: max(40px, var(--a11y-target-min));");
    expect(buttonModuleStyles).toContain("min-height: max(44px, var(--a11y-target-comfort));");
  });

  it("does not block paste in authentication or credential fields", () => {
    const offenders = allSources().filter(({ text }) => /onPaste\s*=|addEventListener\(\s*["']paste["']/.test(text) && /preventDefault\(\)/.test(text));
    expect(offenders.map(({ label }) => label)).toEqual([]);
  });

  it("supports accessible authentication and credential managers", () => {
    expect(loginPage).toContain("loginWithKeycloak");

    const passwordInputs = Array.from(
      connectionForm.matchAll(/<input[\s\S]*?(?:type=\{[^}]*password[^}]*\}|type=["']password["']|register\(["']password["']\))[\s\S]*?\/>/g)
    );
    expect(passwordInputs.length).toBeGreaterThan(0);
    for (const [input] of passwordInputs) {
      expect(input).toMatch(/autoComplete=["'](?:current-password|new-password|one-time-code)["']/);
    }
  });

  it("requires alternatives before drag-only interaction code is introduced", () => {
    const dragSources = allSources().filter(({ text }) => /onDrag|draggable=|addEventListener\(\s*["']drag|onPointerDown|onMouseDown/.test(text));
    for (const { label, text } of dragSources) {
      expect(text, `${label} needs a keyboard or single-pointer alternative for dragging`).toMatch(
        /onKeyDown|aria-keyshortcuts|Move up|Move down|move up|move down|click destination|file input|type=["']file["']/
      );
    }
  });

  it("keeps help affordances in the shared application shell", () => {
    expect(appShell).toContain("ShortcutHelpDialog");
    expect(appShell).toContain("setShortcutHelpOpen(true)");
  });
});
