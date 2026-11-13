# UI-DS-APP — Android debug styleguide screen + Roborazzi screenshots (split from UI-DS; not part of the human gate)

- Phase: P9 · Model: opus (writer-senior) · Risk: low
- Deps: UI-DS, UI-FOUND-APP
- Branch: t/UI-DS-APP-styleguide
- Owned paths: android debug styleguide screen (new, debug source set / debug nav entry), android theme package (consume generated tokens only), docs/design-screenshots/app/ (curated set)
- Module(s) for CI: android (flock /tmp/kotobaya-gradle.lock)

## Read
- docs/DESIGN.md (from UI-DS), design/tokens/, plan/handoffs/UI-FOUND-APP.md (screenshot harness)

## Do
Debug-only styleguide screen rendering every token and component from DESIGN.md in light and dark, using the generated
Compose theme from UI-DS. Add it to the UI-FOUND-APP Roborazzi matrix (phone/foldable/tablet × light/dark × font 1.0/2.0);
commit a curated set under docs/design-screenshots/app/. No changes to real screens.

## Acceptance
- [ ] make check MODULE=android green; styleguide screen screenshot test renders without errors
- [ ] Curated screenshots committed
