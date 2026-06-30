import { describe, expect, it } from "vitest";
import { primitiveTokens, semanticDarkTokens, semanticLightTokens } from "./tokens";

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
  it("uses the requested Obsidian, Amethyst, and Mint theme values", () => {
    expect(semanticLightTokens["--color-bg-base"]).toBe("#FAFAFA");
    expect(semanticLightTokens["--color-bg-surface"]).toBe("#FFFFFF");
    expect(semanticLightTokens["--color-bg-elevated"]).toBe("#F4F4F5");
    expect(semanticLightTokens["--color-text-primary"]).toBe("#09090B");
    expect(semanticLightTokens["--color-text-secondary"]).toBe("#52525B");
    expect(semanticLightTokens["--color-accent-primary"]).toBe("#6D28D9");
    expect(semanticLightTokens["--color-border"]).toBe("#E4E4E7");
    expect(semanticLightTokens["--color-success"]).toBe("#059669");
    expect(semanticLightTokens["--color-warning"]).toBe("#D97706");
    expect(semanticLightTokens["--color-danger"]).toBe("#E11D48");
    expect(semanticLightTokens["--color-info"]).toBe("#2563EB");

    expect(resolveToken(semanticDarkTokens, "--color-bg-base")).toBe("#09090B");
    expect(resolveToken(semanticDarkTokens, "--color-bg-surface")).toBe("#141417");
    expect(resolveToken(semanticDarkTokens, "--color-bg-elevated")).toBe("#27272A");
    expect(resolveToken(semanticDarkTokens, "--color-text-primary")).toBe("#FAFAFA");
    expect(resolveToken(semanticDarkTokens, "--color-text-secondary")).toBe("#A1A1AA");
    expect(resolveToken(semanticDarkTokens, "--color-accent-primary")).toBe("#A78BFA");
    expect(resolveToken(semanticDarkTokens, "--color-border")).toBe("#27272A");
    expect(resolveToken(semanticDarkTokens, "--color-success")).toBe("#34D399");
    expect(resolveToken(semanticDarkTokens, "--color-warning")).toBe("#FBBF24");
    expect(resolveToken(semanticDarkTokens, "--color-danger")).toBe("#FB7185");
    expect(resolveToken(semanticDarkTokens, "--color-info")).toBe("#60A5FA");
  });

  it("keeps status and button foreground tokens above WCAG AA contrast", () => {
    for (const tokens of [semanticLightTokens, semanticDarkTokens]) {
      const surface = resolveToken(tokens, "--color-bg-surface");

      for (const tone of ["success", "warning", "danger", "info"]) {
        const color = resolveToken(tokens, `--color-${tone}`);
        const text = resolveToken(tokens, `--color-${tone}-text`);
        expect(contrast(text, blend(color, surface, 0.16))).toBeGreaterThanOrEqual(4.5);
      }

      expect(contrast(resolveToken(tokens, "--color-accent-contrast"), resolveToken(tokens, "--color-accent-primary"))).toBeGreaterThanOrEqual(
        4.5
      );
      expect(contrast(resolveToken(tokens, "--color-accent-contrast"), resolveToken(tokens, "--color-accent-primary-hover"))).toBeGreaterThanOrEqual(
        4.5
      );
      expect(contrast(resolveToken(tokens, "--color-danger-contrast"), resolveToken(tokens, "--color-danger"))).toBeGreaterThanOrEqual(4.5);
      expect(contrast(resolveToken(tokens, "--color-danger-contrast"), resolveToken(tokens, "--color-danger-hover"))).toBeGreaterThanOrEqual(4.5);
    }
  });
});
