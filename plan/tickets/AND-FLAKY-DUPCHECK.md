# AND-FLAKY-DUPCHECK — DuplicateCheckExpressionTest flakes "No suitable driver"

- Model: sonnet (writer) · Risk: low · Gradle: yes · Owned: android/src/test/**/ankiapi/** (+ the JDBC driver registration it relies on, test-side)
Seen 2–3 times (SHELL-L5, SHELL-L6 handoffs; likely the unidentified 1/647 failure after SHELL-A7). Make it deterministic (explicit `Class.forName`/DriverManager registration or a direct driver instance), run the class 20× green.
