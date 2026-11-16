# WEB-TOAST-REVIEW — Review info toasts cover the Show answer button

- Model: sonnet (writer) · Risk: low · Owned: web/src/lib/ui/** (toast host), web/src/routes/review/** (CSS/markup + toast placement hook), web/playwright/**, web/screenshots/shell/**
## User report (phone shell 0.1.31, 2026-11-17)
The info banner ("marked known", "undo", etc.) covers the Show answer button. Moving toasts to the top is NOT acceptable either: it would cover the top bar's dropdown (⋯) menu.
## Do
On the review screen, toasts must never cover the Show answer / grade bar or the top bar (close, progress, ⋯ menu). Place them in the free band just above the answer/grade bar (offset by its measured height + safe-area), compact, not blocking taps on the card below (pointer-events only on the toast itself). Elsewhere toasts keep their current position. Undo action stays reachable.
## Acceptance
- [ ] Playwright at 360x800 and 412x915 (shell mock): with a toast visible, Show answer, all grade buttons and the ⋯ menu are hit-testable (elementFromPoint at their centres is the button itself), front and back.
- [ ] Screenshots viewed; make check MODULE=web green.
