type TokenMap = Record<`--${string}`, string>;

const STYLE_ELEMENT_ID = "schema-migrator-design-tokens";

export const primitiveTokens = {
  "--color-slate-950": "#0E1117",
  "--color-slate-900": "#161B27",
  "--color-slate-850": "#1E2535",
  "--color-slate-750": "#2A3347",
  "--color-slate-650": "#3D4F6B",
  "--color-slate-500": "#5A6E8A",
  "--color-slate-400": "#8FA0BC",
  "--color-slate-100": "#E8EDF5",
  "--color-blue-500": "#3B82F6",
  "--color-blue-400": "#60A5FA",
  "--color-green-500": "#22C55E",
  "--color-amber-500": "#F59E0B",
  "--color-red-500": "#EF4444",
  "--color-cyan-500": "#06B6D4",
  "--color-white": "#FFFFFF",
  "--color-black": "#020617"
} satisfies TokenMap;

export const semanticDarkTokens = {
  "--color-bg-base": "var(--color-slate-950)",
  "--color-bg-surface": "var(--color-slate-900)",
  "--color-bg-elevated": "var(--color-slate-850)",
  "--color-surface": "var(--color-bg-surface)",
  "--color-surface-strong": "var(--color-bg-elevated)",
  "--color-on-surface": "var(--color-slate-100)",
  "--color-text-primary": "var(--color-slate-100)",
  "--color-text-secondary": "var(--color-slate-400)",
  "--color-text-muted": "var(--color-slate-500)",
  "--color-border": "var(--color-slate-750)",
  "--color-border-strong": "var(--color-slate-650)",
  "--color-accent-primary": "var(--color-blue-500)",
  "--color-accent-primary-hover": "var(--color-blue-400)",
  "--color-danger": "var(--color-red-500)",
  "--color-success": "var(--color-green-500)",
  "--color-warning": "var(--color-amber-500)",
  "--color-info": "var(--color-cyan-500)",
  "--color-overlay": "rgb(2 6 23 / 64%)",
  "--shadow-sm": "0 6px 18px rgb(2 6 23 / 24%)",
  "--shadow-md": "0 16px 42px rgb(2 6 23 / 34%)",
  "--shadow-lg": "0 28px 72px rgb(2 6 23 / 46%)"
} satisfies TokenMap;

export const semanticLightTokens = {
  "--color-bg-base": "#F6F8FB",
  "--color-bg-surface": "#FFFFFF",
  "--color-bg-elevated": "#EEF3F8",
  "--color-surface": "var(--color-bg-surface)",
  "--color-surface-strong": "var(--color-bg-elevated)",
  "--color-on-surface": "#172033",
  "--color-text-primary": "#172033",
  "--color-text-secondary": "#53647F",
  "--color-text-muted": "#73819A",
  "--color-border": "#D5DDE9",
  "--color-border-strong": "#B7C2D2",
  "--color-accent-primary": "#2563EB",
  "--color-accent-primary-hover": "#1D4ED8",
  "--color-danger": "#DC2626",
  "--color-success": "#16A34A",
  "--color-warning": "#D97706",
  "--color-info": "#0891B2",
  "--color-overlay": "rgb(15 23 42 / 42%)",
  "--shadow-sm": "0 6px 18px rgb(15 23 42 / 10%)",
  "--shadow-md": "0 16px 42px rgb(15 23 42 / 14%)",
  "--shadow-lg": "0 28px 72px rgb(15 23 42 / 18%)"
} satisfies TokenMap;

export const scaleTokens = {
  "--font-sans": "\"Inter Variable\", Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
  "--font-mono": "\"JetBrains Mono Variable\", \"JetBrains Mono\", \"SFMono-Regular\", Consolas, \"Liberation Mono\", monospace",
  "--font-size-11": "11px",
  "--font-size-12": "12px",
  "--font-size-13": "13px",
  "--font-size-14": "14px",
  "--font-size-16": "16px",
  "--font-size-20": "20px",
  "--font-size-24": "24px",
  "--font-size-32": "32px",
  "--space-1": "4px",
  "--space-2": "8px",
  "--space-3": "12px",
  "--space-4": "16px",
  "--space-6": "24px",
  "--space-8": "32px",
  "--space-12": "48px",
  "--space-16": "64px",
  "--radius-sm": "4px",
  "--radius-md": "6px",
  "--radius-lg": "8px",
  "--motion-fast": "160ms",
  "--motion-base": "220ms",
  "--motion-slow": "280ms",
  "--ease-standard": "cubic-bezier(0.2, 0, 0, 1)",
  "--ease-emphasized": "cubic-bezier(0.16, 1, 0.3, 1)",
  "--sidebar-width": "280px",
  "--sidebar-collapsed-width": "76px"
} satisfies TokenMap;

const compatibilityTokens = {
  "--bg": "var(--color-bg-base)",
  "--surface": "var(--color-bg-surface)",
  "--surface-strong": "var(--color-bg-elevated)",
  "--text": "var(--color-text-primary)",
  "--muted": "var(--color-text-secondary)",
  "--border": "var(--color-border)",
  "--primary": "var(--color-accent-primary)",
  "--primary-strong": "var(--color-accent-primary-hover)",
  "--primary-soft": "color-mix(in srgb, var(--color-accent-primary) 14%, transparent)",
  "--accent": "var(--color-warning)",
  "--success": "var(--color-success)",
  "--success-soft": "color-mix(in srgb, var(--color-success) 16%, transparent)",
  "--warning": "var(--color-warning)",
  "--warning-soft": "color-mix(in srgb, var(--color-warning) 16%, transparent)",
  "--danger": "var(--color-danger)",
  "--danger-soft": "color-mix(in srgb, var(--color-danger) 16%, transparent)",
  "--info": "var(--color-info)",
  "--info-soft": "color-mix(in srgb, var(--color-info) 16%, transparent)",
  "--shadow": "var(--shadow-md)",
  "--radius": "var(--radius-md)"
} satisfies TokenMap;

export const componentTokenDefaults = {
  button: {
    "--button-bg": "transparent",
    "--button-bg-hover": "transparent",
    "--button-border": "transparent",
    "--button-color": "var(--color-text-primary)"
  },
  input: {
    "--input-bg": "var(--color-bg-surface)",
    "--input-border": "var(--color-border)",
    "--input-border-focus": "var(--color-accent-primary)",
    "--input-color": "var(--color-text-primary)"
  },
  badge: {
    "--badge-bg": "var(--color-bg-elevated)",
    "--badge-color": "var(--color-text-secondary)",
    "--badge-border": "var(--color-border)"
  }
} satisfies Record<string, TokenMap>;

const serializeTokens = (tokens: TokenMap): string =>
  Object.entries(tokens)
    .map(([name, value]) => `  ${name}: ${value};`)
    .join("\n");

const readStoredTheme = (): string => {
  try {
    return window.localStorage.getItem("schemaMigrator.theme") || "dark";
  } catch {
    return "dark";
  }
};

export const createTokenStyleSheet = (): string => {
  const rootTokens = {
    ...primitiveTokens,
    ...scaleTokens,
    ...semanticDarkTokens,
    ...compatibilityTokens
  };
  const lightTokens = {
    ...semanticLightTokens,
    ...compatibilityTokens
  };
  const darkTokens = {
    ...semanticDarkTokens,
    ...compatibilityTokens
  };

  return [
    `:root {\n  color-scheme: dark;\n${serializeTokens(rootTokens)}\n}`,
    `:root[data-theme="dark"] {\n  color-scheme: dark;\n${serializeTokens(darkTokens)}\n}`,
    `:root[data-theme="light"] {\n  color-scheme: light;\n${serializeTokens(lightTokens)}\n}`
  ].join("\n\n");
};

export const installDesignTokens = (): void => {
  if (typeof document === "undefined") {
    return;
  }

  if (!document.documentElement.dataset.theme) {
    document.documentElement.dataset.theme = readStoredTheme();
  }

  const existing = document.getElementById(STYLE_ELEMENT_ID);
  const cssText = createTokenStyleSheet();
  if (existing) {
    existing.textContent = cssText;
    return;
  }

  const style = document.createElement("style");
  style.id = STYLE_ELEMENT_ID;
  style.textContent = cssText;
  document.head.append(style);
};
