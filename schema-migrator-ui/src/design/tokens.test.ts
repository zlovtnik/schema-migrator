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
  it("uses the requested warm operations-console theme values", () => {
    expect(semanticLightTokens["--color-bg-base"]).toBe("#FBF8F1");
    expect(semanticLightTokens["--color-bg-surface"]).toBe("#FFFDF8");
    expect(semanticLightTokens["--color-bg-elevated"]).toBe("#F1EADF");
    expect(semanticLightTokens["--color-text-primary"]).toBe("#201A13");
    expect(semanticLightTokens["--color-text-secondary"]).toBe("#6C6258");
    expect(resolveToken(semanticLightTokens, "--color-accent-primary")).toBe("#8A5200");
    expect(semanticLightTokens["--color-border"]).toBe("#DED2C1");
    expect(semanticLightTokens["--color-success"]).toBe("#257846");
    expect(semanticLightTokens["--color-warning"]).toBe("#9B5E0C");
    expect(semanticLightTokens["--color-danger"]).toBe("#A93C34");
    expect(semanticLightTokens["--color-info"]).toBe("#2F5F9F");

    expect(resolveToken(semanticDarkTokens, "--color-bg-base")).toBe("#100F0D");
    expect(resolveToken(semanticDarkTokens, "--color-bg-surface")).toBe("#171512");
    expect(resolveToken(semanticDarkTokens, "--color-bg-elevated")).toBe("#201E19");
    expect(resolveToken(semanticDarkTokens, "--color-text-primary")).toBe("#EDE9E0");
    expect(resolveToken(semanticDarkTokens, "--color-text-secondary")).toBe("#78726B");
    expect(resolveToken(semanticDarkTokens, "--color-accent-primary")).toBe("#E0963C");
    expect(semanticDarkTokens["--color-border"]).toBe("rgb(255 230 160 / 9%)");
    expect(resolveToken(semanticDarkTokens, "--color-success")).toBe("#6BCB8A");
    expect(resolveToken(semanticDarkTokens, "--color-warning")).toBe("#E0963C");
    expect(resolveToken(semanticDarkTokens, "--color-danger")).toBe("#A93C34");
    expect(resolveToken(semanticDarkTokens, "--color-info")).toBe("#6B94D4");
  });

  it("keeps status and button foreground tokens above WCAG AA contrast", () => {
    for (const tokens of [semanticLightTokens, semanticDarkTokens]) {
      const surface = resolveToken(tokens, "--color-bg-surface");

      for (const tone of ["success", "warning", "danger", "info"]) {
        const color = resolveToken(tokens, `--color-${tone}`);
        const text = resolveToken(tokens, `--color-${tone}-text`);
        expect(contrast(text, blend(color, surface, 0.16))).toBeGreaterThanOrEqual(4.5);
      }

      expect(
        contrast(resolveToken(tokens, "--color-accent-contrast"), resolveToken(tokens, "--color-accent-primary"))
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrast(resolveToken(tokens, "--color-accent-contrast"), resolveToken(tokens, "--color-accent-primary-hover"))
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrast(resolveToken(tokens, "--color-danger-contrast"), resolveToken(tokens, "--color-danger"))
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrast(resolveToken(tokens, "--color-danger-contrast"), resolveToken(tokens, "--color-danger-hover"))
      ).toBeGreaterThanOrEqual(4.5);
    }
  });
});
