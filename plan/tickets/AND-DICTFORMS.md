# AND-DICTFORMS — Android local card search passes C-24 dictForms lookup

- Phase: P10 (0.1.10) · Model: sonnet · Risk: low
- Deps: C-24 merged
- Branch: t/AND-DICTFORMS
- Owned paths: android/**
- Module(s) for CI: android

## Do
C-24 (ADR-025, SCHEMA.md 4.5) made a bare search term match the dictionary forms of entry-linked notes. On SQLite the compiler takes `SearchContext.dictForms` (a compile-time lookup callback against the offline dictionary DB, default null). Android `LocalCardSearch` does not pass it yet, so on device 食べる doesn't find entry-linked notes with empty fields. Wire the callback to the installed offline dictionary (dictionary is a separate SQLite file; off main thread; null when no dictionary installed).
## Acceptance
- [ ] Robolectric/unit test: with a dictionary fixture installed, a bare 食べる / たべる search returns an entry-linked note with empty fields; without a dictionary, behaviour unchanged.
- [ ] make check MODULE=android green.

## Review minors from C-24 (do these here)
- Perf: a short common pattern (る, の, た) makes the dict lookup return a large share of form rows; the seqs are bound as one 100+ KB string scanned by instr per note. Intersect the lookup with the collection's distinct entry seqs before binding (or a temp table), and/or a min length / row cap. Phone with ~20k entry-linked notes must stay well under a second.
- Smart decks (LocalQueueLoader) evaluate the same queries: wire dictForms there too if the same compiler path is used, so device and server smart-deck membership agree.
