# HANDOFF WRK-DICT-LISTSEQ
Status: DONE

- `referenced_seqs` (worker/src/kotobaya_worker/dict/package/source.py) now also unions the members of every live list deck, read directly from `ref.vocab_list_item` (or `ref.entry_freq` for `freq-<slug>-<N>` keys). This is the same resolution `decks.build` uses. So the top60k package carries list-deck seqs even though the `deck-build` jobs are only queued before the package build.
- No change to `dict_refresh.py`: it already calls `build_release`, which calls `referenced_seqs`. No contract change, and top60k scope is unchanged.
- Limit: a list enabled after a release is only included at the next dict-refresh build. Notes added later behave the same, and the phone gap-fills them (SHELL-L9).
- Test: `test_list_deck_seqs_outside_top_n_are_in_top60k_package` in worker/tests/dict/package/test_package.py. The list deck has seqs outside top_n, and they appear in the top60k package. A non-list deck is ignored.
- `make check MODULE=worker`: green (900 passed, mypy and ruff clean). No new dependencies.
