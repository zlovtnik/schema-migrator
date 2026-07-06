type TokenMap = Record<`--${string}`, string>;

const STYLE_ELEMENT_ID = "schema-migrator-design-tokens";

export const primitiveTokens = {
  "--color-slate-950": "#0B1120",
  "--color-slate-900": "#111827",
  "--color-slate-850": "#1F2937",
  "--color-slate-750": "#334155",
  "--color-slate-650": "#475569",
  "--color-slate-500": "#64748B",
  "--color-slate-400": "#CBD5E1",
  "--color-slate-100": "#F8FAFC",
  "--color-blue-500": "#38BDF8",
  "--color-blue-400": "#7DD3FC",
  "--color-green-500": "#34D399",
  "--color-amber-500": "#F59E0B",
  "--color-red-500": "#FB7185",
  "--color-indigo-700": "#1D4ED8",
  "--color-white": "#FFFFFF",
  "--color-black": "#0B1120"
} satisfies TokenMap;

export const semanticDarkTokens = {
  "--color-bg-base": "var(--color-slate-950)",
  "--color-bg-surface": "var(--color-slate-900)",
  "--color-bg-elevated": "var(--color-slate-850)",
  "--color-surface": "var(--color-bg-surface)",
  "--color-surface-strong": "var(--color-bg-elevated)",
  "--color-surface-hover": "var(--color-bg-elevated)",
  "--color-on-surface": "var(--color-slate-100)",
  "--color-text-primary": "var(--color-slate-100)",
  "--color-text-secondary": "var(--color-slate-400)",
  "--color-text-muted": "var(--color-slate-400)",
  "--color-border": "var(--color-slate-750)",
  "--color-border-strong": "var(--color-slate-650)",
  "--color-accent-primary": "var(--color-blue-500)",
  "--color-accent-primary-hover": "var(--color-blue-400)",
  "--color-accent-contrast": "var(--color-slate-950)",
  "--color-danger": "var(--color-red-500)",
  "--color-danger-hover": "#FDA4AF",
  "--color-danger-text": "var(--color-red-500)",
  "--color-danger-contrast": "var(--color-slate-950)",
  "--color-success": "var(--color-green-500)",
  "--color-success-text": "var(--color-green-500)",
  "--color-warning": "var(--color-amber-500)",
  "--color-warning-text": "#FBBF24",
  "--color-info": "var(--color-blue-500)",
  "--color-info-text": "var(--color-blue-500)",
  "--color-schema-accent": "var(--color-accent-primary)",
  "--color-compliance-valid": "var(--color-success)",
  "--color-schema-drift": "var(--color-warning)",
  "--color-critical": "var(--color-danger)",
  "--color-staged": "var(--color-info)",
  "--color-overlay": "rgb(9 9 11 / 72%)",
  "--shadow-sm": "0 1px 2px rgb(0 0 0 / 28%)",
  "--shadow-md": "0 8px 18px rgb(0 0 0 / 22%)",
  "--shadow-lg": "0 14px 32px rgb(0 0 0 / 26%)"
} satisfies TokenMap;

export const semanticLightTokens = {
  "--color-bg-base": "#F8FAFC",
  "--color-bg-surface": "#FFFFFF",
  "--color-bg-elevated": "#F1F5F9",
  "--color-surface": "var(--color-bg-surface)",
  "--color-surface-strong": "var(--color-bg-elevated)",
  "--color-surface-hover": "var(--color-bg-elevated)",
  "--color-on-surface": "#0F172A",
  "--color-text-primary": "#0F172A",
  "--color-text-secondary": "#475569",
  "--color-text-muted": "#475569",
  "--color-border": "#CBD5E1",
  "--color-border-strong": "#94A3B8",
  "--color-accent-primary": "var(--color-indigo-700)",
  "--color-accent-primary-hover": "#1E40AF",
  "--color-accent-contrast": "#FFFFFF",
  "--color-danger": "#E11D48",
  "--color-danger-hover": "#BE123C",
  "--color-danger-text": "#9F1239",
  "--color-danger-contrast": "#FFFFFF",
  "--color-success": "#059669",
  "--color-success-text": "#047857",
  "--color-warning": "#D97706",
  "--color-warning-text": "#92400E",
  "--color-info": "#2563EB",
  "--color-info-text": "#1D4ED8",
  "--color-schema-accent": "var(--color-accent-primary)",
  "--color-compliance-valid": "var(--color-success)",
  "--color-schema-drift": "var(--color-warning)",
  "--color-critical": "var(--color-danger)",
  "--color-staged": "var(--color-info)",
  "--color-overlay": "rgb(9 9 11 / 42%)",
  "--shadow-sm": "0 1px 2px rgb(15 23 42 / 8%)",
  "--shadow-md": "0 6px 14px rgb(15 23 42 / 10%)",
  "--shadow-lg": "0 10px 24px rgb(15 23 42 / 12%)"
} satisfies TokenMap;

export const scaleTokens = {
  "--font-sans": "\"Inter Variable\", Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
  "--font-mono": "\"JetBrains Mono Variable\", \"JetBrains Mono\", \"SFMono-Regular\", Consolas, \"Liberation Mono\", monospace",
  "--font-size-11": "11px",
  "--font-size-12": "12px",
  "--font-size-13": "13px",
  "--font-size-14": "14px",
  "--font-size-16": "16px",
  "--font-size-18": "18px",
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
  "--a11y-target-min": "24px",
  "--a11y-target-comfort": "44px",
  "--a11y-focus-ring-width": "2px",
  "--a11y-focus-ring-offset": "2px",
  "--a11y-focus-obscured-offset": "64px",
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
  "--primary-contrast": "var(--color-accent-contrast)",
  "--primary-soft": "color-mix(in srgb, var(--color-accent-primary) 8%, transparent)",
  "--accent": "var(--color-warning)",
  "--success": "var(--color-success)",
  "--success-text": "var(--color-success-text)",
  "--success-soft": "color-mix(in srgb, var(--color-success) 16%, transparent)",
  "--warning": "var(--color-warning)",
  "--warning-text": "var(--color-warning-text)",
  "--warning-soft": "color-mix(in srgb, var(--color-warning) 16%, transparent)",
  "--danger": "var(--color-danger)",
  "--danger-strong": "var(--color-danger-hover)",
  "--danger-text": "var(--color-danger-text)",
  "--danger-contrast": "var(--color-danger-contrast)",
  "--danger-soft": "color-mix(in srgb, var(--color-danger) 16%, transparent)",
  "--info": "var(--color-info)",
  "--info-text": "var(--color-info-text)",
  "--info-soft": "color-mix(in srgb, var(--color-info) 16%, transparent)",
  "--bg-database": "var(--color-bg-base)",
  "--surface-node": "var(--color-bg-surface)",
  "--surface-node-hover": "var(--color-surface-hover)",
  "--text-column-primary": "var(--color-text-primary)",
  "--text-column-type": "var(--color-text-secondary)",
  "--schema-accent": "var(--color-schema-accent)",
  "--borders-gridlines": "var(--color-border)",
  "--compliance-valid": "var(--color-compliance-valid)",
  "--schema-drift": "var(--color-schema-drift)",
  "--critical": "var(--color-critical)",
  "--staged": "var(--color-staged)",
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
