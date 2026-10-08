type TokenMap = Record<`--${string}`, string>;

const STYLE_ELEMENT_ID = "schema-migrator-design-tokens";

export const primitiveTokens = {
  "--color-obsidian-950": "#090909",
  "--color-obsidian-925": "#141414",
  "--color-obsidian-900": "#141414",
  "--color-obsidian-850": "#1C1C1C",
  "--color-obsidian-800": "#1C1C1C",
  "--color-obsidian-700": "#7D8278",
  "--color-stone-500": "#BEC2B9",
  "--color-stone-100": "#F5F5F2",
  "--color-amber-500": "#F5D08A",
  "--color-green-500": "#A3E6A3",
  "--color-green-400": "#B9EDB9",
  "--color-blue-500": "#B6D5FA",
  "--color-red-500": "#F4B8AE",
  "--color-red-400": "#FFD0C9",
  "--color-black": "#090909"
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
  "--color-text-primary": "var(--color-stone-100)",
  "--color-text-secondary": "var(--color-stone-500)",
  "--color-text-muted": "var(--color-stone-500)",
  "--color-border": "var(--color-obsidian-700)",
  "--color-border-strong": "#BEC2B9",
  "--color-accent-primary": "var(--color-green-500)",
  "--color-accent-primary-hover": "var(--color-green-400)",
  "--color-accent-contrast": "#102010",
  "--color-danger": "var(--color-red-500)",
  "--color-danger-hover": "var(--color-red-400)",
  "--color-danger-text": "var(--color-red-400)",
  "--color-danger-contrast": "var(--color-obsidian-950)",
  "--color-success": "var(--color-green-500)",
  "--color-success-text": "var(--color-green-500)",
  "--color-warning": "var(--color-amber-500)",
  "--color-warning-text": "var(--color-amber-500)",
  "--color-info": "var(--color-blue-500)",
  "--color-info-text": "var(--color-blue-500)",
  "--color-schema-accent": "var(--color-accent-primary)",
  "--color-overlay": "rgb(9 9 9 / 78%)",
  "--surface-glass": "color-mix(in srgb, var(--color-bg-popover) 88%, transparent)",
  "--shadow-sm": "0 1px 2px rgb(0 0 0 / 24%)",
  "--shadow-inset-highlight": "inset 0 1px 0 rgb(255 255 255 / 8%)",
  "--shadow-md": "0 8px 18px rgb(0 0 0 / 20%)",
  "--shadow-lg": "0 16px 36px rgb(0 0 0 / 28%)"
} satisfies TokenMap;

export const scaleTokens = {
  "--font-sans":
    '"Inter Variable", Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  "--font-mono":
    '"JetBrains Mono Variable", "JetBrains Mono", ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace',
  "--font-size-11": "11px",
  "--font-size-12": "12px",
  "--font-size-13": "13px",
  "--font-size-14": "14px",
  "--font-size-16": "16px",
  "--font-size-18": "18px",
  "--font-size-20": "20px",
  "--font-size-24": "24px",
  "--space-1": "4px",
  "--space-2": "8px",
  "--space-3": "12px",
  "--space-4": "16px",
  "--space-5": "20px",
  "--space-6": "24px",
  "--space-8": "32px",
  "--space-12": "48px",
  "--radius-sm": "4px",
  "--radius-md": "6px",
  "--radius-lg": "8px",
  "--elevation-blur-sm": "4px",
  "--elevation-blur-md": "12px",
  "--elevation-blur-lg": "20px",
  "--motion-fast": "160ms",
  "--motion-base": "220ms",
  "--motion-slow": "280ms",
  "--ease-standard": "cubic-bezier(0.2, 0, 0, 1)",
  "--ease-emphasized": "cubic-bezier(0.16, 1, 0.3, 1)",
  "--a11y-target-min": "44px",
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
  "--surface-sidebar": "var(--color-bg-sidebar)",
  "--text": "var(--color-text-primary)",
  "--muted": "var(--color-text-secondary)",
  "--border": "var(--color-border)",
  "--primary": "var(--color-accent-primary)",
  "--primary-strong": "var(--color-accent-primary-hover)",
  "--primary-contrast": "var(--color-accent-contrast)",
  "--primary-soft": "color-mix(in srgb, var(--color-accent-primary) 8%, transparent)",
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
  "--schema-accent": "var(--color-schema-accent)",
  "--shadow": "var(--shadow-md)",
  "--radius": "var(--radius-md)"
} satisfies TokenMap;

const serializeTokens = (tokens: TokenMap): string =>
  Object.entries(tokens)
    .map(([name, value]) => `  ${name}: ${value};`)
    .join("\n");

export const createTokenStyleSheet = (): string => {
  const rootTokens = {
    ...primitiveTokens,
    ...scaleTokens,
    ...semanticDarkTokens,
    ...compatibilityTokens
  };
  return `:root {\n  color-scheme: dark;\n${serializeTokens(rootTokens)}\n}`;
};

export const installDesignTokens = (): void => {
  if (typeof document === "undefined") {
    return;
  }

  document.documentElement.dataset.theme = "dark";

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
