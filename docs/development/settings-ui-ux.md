# Settings UI and UX Contract

This document defines the visual, interaction, copy, and asset-import rules for
LxChat settings surfaces. New settings pages and revisions to existing pages
must follow the same contract.

## Structure and spacing

- Use the shared settings page scaffold and `SettingsItem` for standard rows.
- Use `SettingsAddItem` for add actions. Its layout is fixed: 56 dp minimum
  height, 18 dp add icon, 8 dp icon-to-label gap, `labelLarge`, centered
  content, and a full-row ripple.
- Keep counts and status details on a second line. Do not place them beside the
  primary label.
- Destructive actions use the error color. Primary creation and save actions
  use the primary color. Disabled appearance is owned by the component.
- Only passive next-page chevrons use reduced alpha. Details, more, refresh,
  external-link, and other action icons use the full `onSurfaceVariant` color.
- External links use the external-link icon, not a next-page chevron.

## Motion and state

- State changes inside a fixed layout slot use a 250 ms crossfade.
- Async labels, leading icons, counts, and connection status must change in
  place without shifting the surrounding layout.
- Rows remain clickable as a whole and expose one clear semantic action.

## Copy and localization

- English page, group, row, action, and dialog titles use Title Case.
- Technical acronyms remain uppercase: MCP, API, URL, HTTP, SSE, SSH, PDF, and
  GGUF.
- Field labels, descriptions, and status text use sentence case. Status text
  has no trailing period.
- Confirmation dialog titles are bold and end with a question mark.
- Other locales use their native casing and punctuation conventions.
- Every default string key must exist in every supported locale.

## Vector asset import

- Preserve SVG `fill-rule` and `clip-rule` when converting to Android vectors.
  In particular, SVG `evenodd` becomes `android:fillType="evenOdd"` on every
  affected path.
- Normalize path data before import. Arc flags must be separate numeric tokens;
  compact SVG sequences such as `00-3.61`, `01-1.203`, or `010.016` are not
  accepted in Android VectorDrawable resources.
- Complex paths must retain enough inset for anti-aliasing. Do not place a
  geometric extremum directly on a viewport edge; apply a centered, uniform
  group transform when the upstream asset has no safety margin.
- Run the resource contract tests after importing or replacing any complex
  provider or integration icon.
