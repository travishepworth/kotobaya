# WRK-DICT-LISTSEQ — Device dictionary package misses list-deck words

- Model: sonnet (writer) · Risk: med · Owned: worker/ (dict package build + dict_refresh) · Module: worker
## Root cause (SHELL-L9, 2026-11-16)
`worker/.../dict/package/source.py` adds referenced seqs to `top60k` only when dict-refresh builds a release, and
`dict_refresh.py` builds packages right after `_requeue_decks` merely *enqueues* list-deck rebuilds. So list-deck items
(e.g. the conversation-focused list) and notes added later that fall outside the top 60k are not in the package until the
next JMdict release. The phone now gap-fills them (SHELL-L9), but the package should carry them.
## Do
Make the referenced-seq set include every seq a list deck will reference (materialize first, or read list membership
directly), and/or rebuild the top60k package (a diff) when list decks / notes add referenced seqs outside it, bounded.
## Acceptance
- [ ] Test: a list deck with a seq outside the top-60k ranking → that seq is in the next top60k package (fixture).
- [ ] make check MODULE=worker green.
