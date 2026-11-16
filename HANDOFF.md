# HANDOFF AND-FLAKY-DUPCHECK — DONE
- Root cause: `DriverManager.getConnection` offers a driver only to a caller whose classloader can load that exact driver class. After Robolectric sandbox classes (own loader) run in the same JVM, the service-loaded `org.sqlite.JDBC` registration can belong to another loader, so a later plain-JVM `JdbcSqliteDriver` gets "No suitable driver" (order-dependent, hence rare).
- Fix (test-side only): `ensureSqliteJdbc()` in `android/src/test/kotlin/kotobaya/android/sync/SyncTestSupport.kt` registers an `org.sqlite.JDBC` of the current loader if none of that exact class is registered (reflection; org.sqlite is not on the compile classpath). Called from `DbFile.open()` and `memoryDriver()`, which cover SqlDevice (DuplicateCheckExpressionTest).
- Not changed: other tests that call `JdbcSqliteDriver(...)`/`DriverManager` directly (DeckRepositoryTest etc.); call `ensureSqliteJdbc()` there if any flakes.
- Verified: DuplicateCheckExpressionTest 20x in a loop (`--rerun`): 20/20 ok. `make check MODULE=android` green (full suite).
- No new deps. Root cause is inferred from DriverManager semantics; the flake was not reproduced pre-fix.
