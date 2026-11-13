# HANDOFF F-22

Status: DONE (tests only)

- Item 2 (`*` pos levels) already done in matcher.py `_pos` (trailing `*` dropped only). Added test_middle_star_pos_level_is_kept_so_deep_rules_match.
- Item 3 (give-ups) already done: MatchStats counts, detect() logs a warning and returns Detection.give_ups. Added test_detect_counts_budget_give_ups (5 repeats needed to exhaust budget; the 4-repeat test rule in test_matcher never gives up, left as is).
- make check MODULE=worker green (759 passed).
- No new deps; no contract changes.
