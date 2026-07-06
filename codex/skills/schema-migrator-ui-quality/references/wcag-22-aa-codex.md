# Schema Migrator UI/UX Codex

These rules are hard requirements for `schema-migrator-ui/`. They translate WCAG 2.2 AA additions and the local enterprise design system into reviewable implementation constraints.

## WCAG 2.2 AA Rules

[RULE: A11Y_FOCUS_OBSCURED_2.4.11]
Scope: Global layout, keyboard navigation, anchored content, modals, drawers.
Standard: WCAG 2.2 AA, 2.4.11 Focus Not Obscured (Minimum).
Logic:
IF an element receives `:focus` or `:focus-visible`
THEN its bounding box must not be fully hidden by fixed or sticky UI.
Validation:
Use `scroll-padding` on scroll containers and `scroll-margin` on focusable or anchor-targeted elements. Fixed overlays must be modal, dismissible, and must not allow background focus.

[RULE: A11Y_DRAGGING_2.5.7]
Scope: Sliders, reorderable lists, drag-and-drop upload zones, Kanban boards, split panes.
Standard: WCAG 2.2 AA, 2.5.7 Dragging Movements.
Logic:
IF a component uses drag-and-drop or pointer-drag listeners
THEN an equivalent single-pointer or keyboard path must exist in the DOM.
Validation:
Provide Move Up/Move Down buttons, click-select/click-destination controls, native file input fallback, or numeric inputs for slider values.

[RULE: A11Y_TARGET_SIZE_2.5.8]
Scope: Buttons, links, icon buttons, menu items, checkbox/radio controls, custom controls.
Standard: WCAG 2.2 AA, 2.5.8 Target Size (Minimum).
Logic:
IF an element is interactive
THEN its target must be at least 24px by 24px, or have enough spacing to fit a 24px diameter clearance area.
Exceptions:
Inline text links, browser-default controls, user-agent-controlled widgets, and controls where the target size is essential.
Validation:
Use `--a11y-target-min` for the enforced floor and prefer `--a11y-target-comfort` for primary touch surfaces.

[RULE: A11Y_CONSISTENT_HELP_3.2.6]
Scope: Help, support, contact, documentation, and keyboard-shortcut affordances.
Standard: WCAG 2.2 AA, 3.2.6 Consistent Help.
Logic:
IF help is available on multiple pages
THEN it must appear in the same relative order and location across those pages.
Validation:
Keep global help in `AppShell` or another shared layout. Do not create page-local help entry points that reorder or duplicate the global affordance.

[RULE: A11Y_REDUNDANT_ENTRY_3.3.7]
Scope: Multi-step forms, target setup, registration, checkout-like workflows, repeated confirmation data.
Standard: WCAG 2.2 AA, 3.3.7 Redundant Entry.
Logic:
IF data was already entered in the current session or current workflow
THEN do not require re-entry unless the data is security-sensitive, stale, or required to verify intent.
Validation:
Persist step-wizard values in component state, route state, or storage as appropriate. Offer "use previous" or "same as" choices when repeated data is necessary.

[RULE: A11Y_AUTH_3.3.8]
Scope: Login, SSO, password, password reset, token, and credential forms.
Standard: WCAG 2.2 AA, 3.3.8 Accessible Authentication (Minimum).
Logic:
IF a view requires authentication
THEN it must support password managers and assistive authentication flows, and must not require a cognitive-function test without assistance.
Validation:
Use correct `autoComplete` values. Do not block paste. Prefer SSO/OAuth/WebAuthn or other third-party authenticators where available.

## Design-System Rules

[RULE: TOKEN_DICTIONARY_01]
Scope: Colors, fonts, spacing, radii, motion, accessibility constants.
Logic:
IF styling needs a value already represented in `src/design/tokens.ts`
THEN use that token instead of a new literal.
Validation:
Extend tokens first when a new reusable value is needed.

[RULE: TYPOGRAPHY_HIERARCHY_01]
Scope: Headings, body text, dense tables, code/log output.
Logic:
IF rendering text
THEN use the Inter sans stack for interface text and JetBrains Mono for code, SQL, checksums, IDs, and logs.
Validation:
Use `--font-sans`, `--font-mono`, and the `--font-size-*` tokens.

[RULE: CONTRAST_AA_01]
Scope: Text, buttons, status chips, icons that convey meaning.
Logic:
IF text or meaningful iconography is rendered against a surface
THEN normal text contrast must be at least 4.5:1.
Validation:
Keep token contrast tests current for light and dark themes.

[RULE: MOTION_REDUCED_01]
Scope: Transitions, page entry animation, drawer/modal animation, hover motion.
Logic:
IF animation or transition is introduced
THEN it must use existing motion tokens, avoid layout-shifting properties, and honor `prefers-reduced-motion`.
Validation:
Add reduced-motion CSS for any new moving element.
