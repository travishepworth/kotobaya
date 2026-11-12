# HANDOFF T302

STATUS: DONE
BRANCH: t/T302-device-dict
CHECK: `make check MODULE=android` green; `MODULE=core` green (jvm + js)

## Changed
- `android/.../dict/`: DictInstaller (DICT_PACKAGE 5), PackageFile (digests 3.5, diff apply 3.4, atomic swap), InstalledDictionary (read-only search/entry/kanji/examples), HttpDictRemote, DictFeature (Koin, WorkManager, Settings > Offline storage).
- `core/.../kotobaya/dict/Romaji.kt` plus parity test (jvm+js). DUPLICATED from server `dict/Romaji.kt`; keep in step.
- FLAGS: ONE line in `di/FeatureModules.kt`; no .sq needed. Outside owned paths: `android/build.gradle.kts` (deps), `res/values/strings_dict.xml`.

## Acceptance -> tests
Installer cases, dispatcher, wrong-base: `DictInstallerTest`. Offline search + the 50 T106 queries: `OfflineSearchTest` (builds a package from the server fixture dump; first results equal the server's, no differences).

## New dependencies
- zstd-jni 1.5.6-4 (aar for app, jar for host tests): no stdlib zstd. work-runtime-ktx 2.10.0: update job.

## For the reviewer
- Not wired (not my paths): app start should call `DictWork.ensurePeriodic` and `installer.startup()` (Settings and the worker already do). `db.DictionaryProvider` seam is unused; `InstalledDictionary` supersedes it.
- Diff JSON is parsed in memory (org.json); fine for normal refresh diffs. Search has no status/note overlay.
- Unverified: real zstd-jni on a device; Range resume over HTTP (only the fake remote is tested).
