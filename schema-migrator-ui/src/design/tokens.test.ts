import { afterEach, describe, expect, it, vi } from "vitest";
import { createTokenStyleSheet, installDesignTokens, primitiveTokens, scaleTokens, semanticDarkTokens } from "./tokens";
import { sqlHighlightTheme } from "./sqlHighlightTheme";

const resolveToken = (tokens: Record<string, string>, name: string): string => {
  const value = tokens[name];
  if (!value) {
    throw new Error(`Missing token ${name}`);
  }

  const match = value?.match(/^var\((--[^)]+)\)$/);
  return match?.[1] ? resolveToken({ ...primitiveTokens, ...tokens }, match[1]) : value;
};

const rgb = (hex: string): [number, number, number] => {
  const parts = hex.slice(1).match(/.{2}/g);
  if (parts?.length !== 3) {
    throw new Error(`Invalid hex color ${hex}`);
  }

  const [red, green, blue] = parts as [string, string, string];
  return [Number.parseInt(red, 16), Number.parseInt(green, 16), Number.parseInt(blue, 16)];
};

const toHex = (channels: [number, number, number]): string =>
  `#${channels.map((channel) => Math.round(channel).toString(16).padStart(2, "0")).join("")}`.toUpperCase();

const blend = (front: string, back: string, alpha: number): string => {
  const frontRgb = rgb(front);
  const backRgb = rgb(back);
  return toHex([
    frontRgb[0] * alpha + backRgb[0] * (1 - alpha),
    frontRgb[1] * alpha + backRgb[1] * (1 - alpha),
    frontRgb[2] * alpha + backRgb[2] * (1 - alpha)
  ]);
};

const luminance = (hex: string): number => {
  const linearize = (channel: number): number => {
    const normalized = channel / 255;
    return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
  };
  const [red, green, blue] = rgb(hex).map(linearize) as [number, number, number];
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
};

const contrast = (a: string, b: string): number => {
  const first = luminance(a);
  const second = luminance(b);
  const lighter = Math.max(first, second);
  const darker = Math.min(first, second);
  return (lighter + 0.05) / (darker + 0.05);
};

describe("design color tokens", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.removeItem("schemaMigrator.theme");
  });

  it("uses the shared RCLabs dark-only palette", () => {
    expect(resolveToken(semanticDarkTokens, "--color-bg-base")).toBe("#090909");
    expect(resolveToken(semanticDarkTokens, "--color-bg-surface")).toBe("#141414");
    expect(resolveToken(semanticDarkTokens, "--color-bg-elevated")).toBe("#1C1C1C");
    expect(resolveToken(semanticDarkTokens, "--color-text-primary")).toBe("#F5F5F2");
    expect(resolveToken(semanticDarkTokens, "--color-text-secondary")).toBe("#BEC2B9");
    expect(resolveToken(semanticDarkTokens, "--color-accent-primary")).toBe("#A3E6A3");
    expect(semanticDarkTokens["--surface-glass"]).toContain("transparent");
    expect(scaleTokens["--elevation-blur-sm"]).toBe("4px");
    expect(scaleTokens["--elevation-blur-md"]).toBe("12px");
    expect(scaleTokens["--elevation-blur-lg"]).toBe("20px");
    expect(createTokenStyleSheet()).not.toMatch(/color-scheme: light|data-theme="light"|prefers-color-scheme/);
  });

  it.each(["light", "system", "dark", "invalid"])("ignores the saved %s theme and existing root preference", (saved) => {
    window.localStorage.setItem("schemaMigrator.theme", saved);
    document.documentElement.dataset.theme = saved;
    installDesignTokens();
    installDesignTokens();
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(document.querySelectorAll("#schema-migrator-design-tokens")).toHaveLength(1);
  });

  it("installs dark tokens when local storage is unavailable", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("Storage blocked"); });
    expect(() => installDesignTokens()).not.toThrow();
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("keeps normal text at 7:1 and controls/focus at 3:1 across the dark surfaces", () => {
    const tokens = semanticDarkTokens;
    for (const name of ["base", "surface", "elevated", "popover", "sidebar"]) {
      const surface = resolveToken(tokens, `--color-bg-${name}`);
      for (const role of ["primary", "secondary", "muted"]) {
        expect(contrast(resolveToken(tokens, `--color-text-${role}`), surface), `${role} on ${name}`).toBeGreaterThanOrEqual(7);
      }
      for (const role of ["border", "border-strong", "accent-primary"]) {
        expect(contrast(resolveToken(tokens, `--color-${role}`), surface), `${role} on ${name}`).toBeGreaterThanOrEqual(3);
      }

      for (const tone of ["success", "warning", "danger", "info"]) {
        const color = resolveToken(tokens, `--color-${tone}`);
        const text = resolveToken(tokens, `--color-${tone}-text`);
        expect(contrast(text, blend(color, surface, 0.16)), `${tone} on ${name}`).toBeGreaterThanOrEqual(7);
      }
    }
    for (const suffix of ["", "-hover"]) {
      expect(
        contrast(resolveToken(tokens, "--color-accent-contrast"), resolveToken(tokens, `--color-accent-primary${suffix}`))
      ).toBeGreaterThanOrEqual(7);
      expect(
        contrast(resolveToken(tokens, "--color-danger-contrast"), resolveToken(tokens, `--color-danger${suffix}`))
      ).toBeGreaterThanOrEqual(7);
    }
  });

  it("keeps SQL syntax and comments at 7:1", () => {
    const background = sqlHighlightTheme.colors["editor.background"];
    for (const color of [sqlHighlightTheme.colors["editor.foreground"], ...sqlHighlightTheme.tokenColors.map(({ settings }) => settings.foreground)]) {
      expect(contrast(color, background)).toBeGreaterThanOrEqual(7);
    }
  });
});
