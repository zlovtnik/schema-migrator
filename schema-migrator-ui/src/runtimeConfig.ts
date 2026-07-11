type RuntimeConfig = Partial<Record<string, string>>;

declare global {
  interface Window {
    __SCHEMA_MIGRATOR_CONFIG__?: RuntimeConfig;
  }
}

export const runtimeConfig = (key: string): string | undefined => {
  const value = window.__SCHEMA_MIGRATOR_CONFIG__?.[key];
  return typeof value === "string" ? value : undefined;
};
