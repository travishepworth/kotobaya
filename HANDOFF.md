# LOOP-31-AND — DONE

- `keep_going_new_target` parsed like the server in `QueueRoute.kt`: decimal digits only, else 422 `invalid_value` at `/keep_going_new_target`; above 2147483647 gives `out_of_range`. It is validated even without `keep_going=true`, then ignored. It is passed to `QueueLimits.keepGoingNewTarget`, and only when keep going is on.
- `LOCAL_API_VERSION` is now 3.
- `LocalQueueLoader.load` gained a `keepGoingNewTarget: Int? = null` parameter. It also goes into the `LoadGate` key. This is outside the ticket's listed owned paths, but the loader had no way to take a target. Native surfaces (widget, notification) are unchanged and pass no target.
- `ParityExpectTest` passes, including queue-keep-going-target.
- Added no new tests. The queue-keep-going-target parity scenario covers the 422 pointers and the batches, and its golden is not recorded yet (LF-GOLD).
- Added no new dependencies.

Remaining expected failures on t/LF-int (3 of 671 tests):
- `RouteTableTest.everyLocalOperationIsRegisteredOrPending`
- `LocalParityTest.everyScenarioWithAllOperationsRegisteredMatchesItsGolden`
- `LocalParityTest.goldensAndScenariosAreOneToOne`

Both LocalParityTest failures include "no golden queue-keep-going-target.json". LF-GOLD records it.

I ran `:android:testDebugUnitTest` only, not the full `make check MODULE=android`.
