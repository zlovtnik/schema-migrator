import { primitiveTokens } from "./tokens";

// SQL colors use the same tested palette as surrounding interface text.
export const sqlHighlightTheme = {
  name: "rclabs-dark",
  type: "dark" as const,
  colors: {
    "editor.background": primitiveTokens["--color-obsidian-900"],
    "editor.foreground": primitiveTokens["--color-stone-100"]
  },
  tokenColors: [
    { scope: ["comment", "punctuation.definition.comment"], settings: { foreground: primitiveTokens["--color-stone-500"] } },
    { scope: ["keyword", "storage"], settings: { foreground: primitiveTokens["--color-green-500"] } },
    { scope: ["string"], settings: { foreground: primitiveTokens["--color-amber-500"] } },
    { scope: ["constant", "entity.name", "support"], settings: { foreground: primitiveTokens["--color-blue-500"] } },
    { scope: ["invalid"], settings: { foreground: primitiveTokens["--color-red-400"] } }
  ]
};
