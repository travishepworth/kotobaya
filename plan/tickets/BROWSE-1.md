# BROWSE-1 — Browse search should match the word's surface form (e.g. 食べる)

- Phase: P10/P11 (0.1.10 if small, else 0.1.11) · Model: sonnet · Risk: med
- Deps: none
- Branch: t/BROWSE-1-surface-search
- Owned paths: web/src/** (browse/cards search wiring), server/** browse/search query handling if the server evaluates the parsed query, core search parser tests only if no contract change is needed
- Module(s) for CI: web (+ server/core if touched)

## Do
User report (web): typing the actual word (surface/headword, e.g. 食べる) in Browse search doesn't find its cards. Diagnose where the term is lost: core search parser (no default field for bare terms / no headword field), the server query (searches only some fields), or the web wiring. Search parsing MUST come from core (web/src/lib/core); never re-implement in TS. If core's parser/contract lacks a field or default-field rule for this, return CONTRACT_CHANGE_REQUEST describing the minimal addition (pre-approved; orchestrator opens a C-ticket for architect).
## Acceptance
- [ ] Bare term 食べる matches notes whose headword/surface (kanji or kana form) is 食べる; also reading たべる. Named test at the layer where the bug lived.
- [ ] make check for touched modules green.
