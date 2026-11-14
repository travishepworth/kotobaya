# HANDOFF AND-WIDGET

STATUS: DONE
BRANCH: t/AND-WIDGET-row10
CHECK: `make check MODULE=android` → green (exit 0)

## Changed
- KotobayaWidget.kt: the crash was the Forecast Row (24 bars x 2 children = 48), not Progress. Forecast now sums hourly counts into at most 8 bars (`forecastBars`), one child each.
- WidgetTest.kt: added a test for the bar count.

## Acceptance → tests
- Row ≤10 children for any total incl. 48+ → `forecastRowNeverExceedsTenChildren`
- make check green → yes

## New dependencies
- none

## For the reviewer
- The test checks `forecastBars` (the Row's child count) rather than counting Glance nodes.
- The Progress Row has at most 1 child and was not changed.
- The forecast chart is now 8 three-hour bars instead of 24 hourly bars.
