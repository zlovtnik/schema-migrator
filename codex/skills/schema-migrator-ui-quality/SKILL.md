---
name: schema-migrator-ui-quality
description: Validate and harden the Schema Migrator React UI against the repository UI/UX codex, design tokens, and WCAG 2.2 AA accessibility rules. Use when changing schema-migrator-ui components, CSS, layouts, forms, authentication views, drag interactions, navigation, focus behavior, or design tokens.
---

# Schema Migrator UI Quality

## Overview

Use this skill to review or implement UI changes in `schema-migrator-ui/` with the same enforceable accessibility and design-system rules used by local tests. Keep the interface dense, operational, and keyboard-safe.

## Workflow

1. Read `references/wcag-22-aa-codex.md` before changing UI surfaces.
2. Inspect the affected React component, CSS module/global CSS, and tests.
3. Prefer existing tokens from `src/design/tokens.ts` and existing controls under `src/components/ui/`.
4. Apply the rule catalog as hard requirements, especially target size, focus visibility, authentication, drag alternatives, redundant entry, and consistent help.
5. Add or update Vitest coverage when a rule can be checked statically or with React Testing Library.
6. Run `bun run test` from `schema-migrator-ui/` before delivery when dependencies are available.

## Rule Sources

- `src/design/tokens.ts`: primitive, semantic, motion, spacing, and accessibility constants.
- `src/design/accessibilityCodex.test.ts`: static guardrails for WCAG 2.2 AA regressions.
- `src/design/tokens.test.ts`: contrast and token validation.
- `src/styles.css` and `src/components/ui/*.module.css`: canonical focus rings, targets, reduced motion, and component sizing.
- `references/wcag-22-aa-codex.md`: full human-readable rule catalog.

## Implementation Rules

- Do not introduce new one-off colors, font stacks, radii, motion durations, or focus styles when a token exists.
- Keep interactive controls at least `--a11y-target-min` by `--a11y-target-min`; prefer `--a11y-target-comfort` on primary touch surfaces.
- Use `:focus-visible` outlines from the a11y focus tokens and keep focused elements away from sticky/fixed overlays with scroll padding or scroll margin.
- Do not block paste, password managers, OAuth, WebAuthn, or other assistive authentication mechanisms.
- Do not build drag-only controls. Add keyboard buttons or a click-select/click-destination path.
- Persist previously entered multi-step form data unless it is explicitly security-sensitive.
- Keep help affordances in a consistent global location. This app uses `ShortcutHelpDialog` from `AppShell`.
- Respect `prefers-reduced-motion`; avoid layout-shifting hover/focus animation.

## Validation

Run from `schema-migrator-ui/`:

```bash
bun run test src/design/accessibilityCodex.test.ts src/design/tokens.test.ts
```

For broader UI changes, run the full UI test suite:

```bash
bun run test
```
