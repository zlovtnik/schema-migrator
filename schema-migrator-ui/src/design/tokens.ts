type TokenMap = Record<`--${string}`, string>;

const STYLE_ELEMENT_ID = "schema-migrator-design-tokens";

export const primitiveTokens = {
  "--color-obsidian-950": "#100F0D",
  "--color-obsidian-925": "#141210",
  "--color-obsidian-900": "#171512",
  "--color-obsidian-850": "#1D1B16",
  "--color-obsidian-800": "#201E19",
  "--color-obsidian-700": "#3A3730",
  "--color-stone-500": "#78726B",
  "--color-stone-100": "#EDE9E0",
  "--color-amber-500": "#E0963C",
  "--color-amber-600": "#D8852D",
  "--color-green-500": "#6BCB8A",
  "--color-blue-500": "#6B94D4",
  "--color-red-500": "#A93C34",
  "--color-red-400": "#F0AAA4",
  "--color-plum-500": "#A17BC4",
  "--color-white": "#FFFFFF",
  "--color-black": "#100F0D"
} satisfies TokenMap;

export const semanticDarkTokens = {
  "--color-bg-base": "var(--color-obsidian-950)",
  "--color-bg-surface": "var(--color-obsidian-900)",
  "--color-bg-elevated": "var(--color-obsidian-800)",
  "--color-bg-popover": "var(--color-obsidian-850)",
  "--color-bg-sidebar": "var(--color-obsidian-925)",
  "--color-surface": "var(--color-bg-surface)",
  "--color-surface-strong": "var(--color-bg-elevated)",
  "--color-surface-hover": "var(--color-bg-elevated)",
  "--color-on-surface": "var(--color-stone-100)",
  "--color-text-primary": "var(--color-stone-100)",
  "--color-text-secondary": "var(--color-stone-500)",
  "--color-text-muted": "var(--color-stone-500)",
  "--color-border": "rgb(255 230 160 / 9%)",
  "--color-border-strong": "rgb(255 230 160 / 18%)",
  "--color-accent-primary": "var(--color-amber-500)",
  "--color-accent-primary-hover": "var(--color-amber-600)",
  "--color-accent-contrast": "var(--color-obsidian-950)",
  "--color-danger": "var(--color-red-500)",
  "--color-danger-hover": "#8F322C",
  "--color-danger-text": "var(--color-red-400)",
  "--color-danger-contrast": "var(--color-stone-100)",
  "--color-success": "var(--color-green-500)",
  "--color-success-text": "var(--color-green-500)",
  "--color-warning": "var(--color-amber-500)",
  "--color-warning-text": "var(--color-amber-500)",
  "--color-info": "var(--color-blue-500)",
  "--color-info-text": "var(--color-blue-500)",
  "--color-schema-accent": "var(--color-accent-primary)",
  "--color-compliance-valid": "var(--color-success)",
  "--color-schema-drift": "var(--color-warning)",
  "--color-critical": "var(--color-danger)",
  "--color-staged": "var(--color-info)",
  "--color-overlay": "rgb(8 7 6 / 78%)",
  "--shadow-sm": "0 1px 2px rgb(0 0 0 / 24%)",
  "--shadow-inset-highlight": "inset 0 1px 0 rgb(255 255 255 / 8%)",
  "--shadow-md": "0 8px 18px rgb(0 0 0 / 20%)",
  "--shadow-lg": "0 16px 36px rgb(0 0 0 / 28%)"
} satisfies TokenMap;

export const semanticLightTokens = {
  "--color-bg-base": "#FBF8F1",
  "--color-bg-surface": "#FFFDF8",
  "--color-bg-elevated": "#F1EADF",
  "--color-bg-popover": "#FFFDF8",
  "--color-bg-sidebar": "#F4EDE2",
  "--color-surface": "var(--color-bg-surface)",
  "--color-surface-strong": "var(--color-bg-elevated)",
  "--color-surface-hover": "var(--color-bg-elevated)",
  "--color-on-surface": "#201A13",
  "--color-text-primary": "#201A13",
  "--color-text-secondary": "#6C6258",
  "--color-text-muted": "#6C6258",
  "--color-border": "#DED2C1",
  "--color-border-strong": "#BFAF9B",
  "--color-accent-primary": "#8A5200",
  "--color-accent-primary-hover": "#6F4200",
  "--color-accent-contrast": "#FFFDF8",
  "--color-danger": "#A93C34",
  "--color-danger-hover": "#8F322C",
  "--color-danger-text": "#7C2D27",
  "--color-danger-contrast": "#FFFDF8",
  "--color-success": "#257846",
  "--color-success-text": "#14592E",
  "--color-warning": "#9B5E0C",
  "--color-warning-text": "#6F4200",
  "--color-info": "#2F5F9F",
  "--color-info-text": "#244A7D",
  "--color-schema-accent": "var(--color-accent-primary)",
  "--color-compliance-valid": "var(--color-success)",
  "--color-schema-drift": "var(--color-warning)",
  "--color-critical": "var(--color-danger)",
  "--color-staged": "var(--color-info)",
  "--color-overlay": "rgb(32 26 19 / 42%)",
  "--shadow-sm": "0 1px 2px rgb(58 45 27 / 8%)",
  "--shadow-inset-highlight": "inset 0 1px 0 rgb(255 255 255 / 70%)",
  "--shadow-md": "0 6px 14px rgb(58 45 27 / 10%)",
  "--shadow-lg": "0 10px 24px rgb(58 45 27 / 12%)"
} satisfies TokenMap;

export const scaleTokens = {
  "--font-sans":
    '"Inter Variable", Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  "--font-mono":
    '"JetBrains Mono Variable", "JetBrains Mono", "SFMono-Regular", Consolas, "Liberation Mono", monospace',
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
  "--space-5": "20px",
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
  "--sidebar-width": "224px",
  "--sidebar-collapsed-width": "56px"
} satisfies TokenMap;

const compatibilityTokens = {
  "--bg": "var(--color-bg-base)",
  "--surface": "var(--color-bg-surface)",
  "--surface-strong": "var(--color-bg-elevated)",
  "--surface-popover": "var(--color-bg-popover)",
  "--surface-sidebar": "var(--color-bg-sidebar)",
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
